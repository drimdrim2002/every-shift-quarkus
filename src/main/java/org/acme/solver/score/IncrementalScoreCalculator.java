package org.acme.solver.score;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.move.ConstraintImpact;
import org.acme.solver.move.MoveImpact;

/**
 * 제약 evaluator를 shift/employee 단위 캐시로 분할하는 증분 점수 계산기입니다.
 * refresh는 모든 새 셀과 최종 score의 overflow를 검증한 뒤 한 번에 cache를 교체합니다.
 */
public final class IncrementalScoreCalculator {

    private final PlanningProblem problem;
    private final FullScoreCalculator fullScoreCalculator;
    private final List<ConstraintEvaluator> evaluators;
    private final List<ConstraintDependencyMetadata> dependencyMetadata;
    private final List<RosterScore[]> scoreCache;
    private RosterScore currentScore;
    private RosterSolution lastVerifiedBest;

    public IncrementalScoreCalculator(PlanningProblem problem, RosterSolution initialSolution) {
        this(problem, initialSolution, new FullScoreCalculator());
    }

    public IncrementalScoreCalculator(
            PlanningProblem problem,
            RosterSolution initialSolution,
            FullScoreCalculator fullScoreCalculator) {
        this.problem = Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(initialSolution, "initialSolution");
        this.fullScoreCalculator = Objects.requireNonNull(fullScoreCalculator, "fullScoreCalculator");
        validateSolutionShape(initialSolution);
        this.evaluators = fullScoreCalculator.evaluators();

        List<ConstraintDependencyMetadata> metadata = new ArrayList<>(evaluators.size());
        Set<String> evaluatorIds = new HashSet<>();
        for (ConstraintEvaluator evaluator : evaluators) {
            ConstraintDependencyMetadata dependency = Objects.requireNonNull(
                    evaluator.dependencyMetadata(),
                    () -> "dependency metadata가 없습니다: " + evaluator.evaluatorId());
            if (!evaluator.evaluatorId().equals(dependency.evaluatorId())) {
                throw new IllegalArgumentException(
                        "evaluatorId와 metadata ID가 다릅니다: " + evaluator.evaluatorId());
            }
            if (!evaluatorIds.add(evaluator.evaluatorId())) {
                throw new IllegalArgumentException("중복 evaluatorId입니다: " + evaluator.evaluatorId());
            }
            metadata.add(dependency);
        }
        this.dependencyMetadata = List.copyOf(metadata);

        RosterScore verifiedFullScore = fullScoreCalculator.calculateScore(problem, initialSolution);
        RosterSolution verifiedInitial = withScore(initialSolution, verifiedFullScore);
        this.lastVerifiedBest = verifiedInitial;
        if (!verifiedFullScore.equals(initialSolution.score())) {
            throw new ScoreMismatchException(
                    "초기 solution score가 FullScoreCalculator 결과와 다릅니다.",
                    initialSolution.score(), verifiedFullScore, verifiedInitial);
        }

        ScoreEvaluationContext baseContext = new ScoreEvaluationContext(problem, initialSolution);
        this.scoreCache = new ArrayList<>(evaluators.size());
        long[] total = new long[5];
        for (int evaluatorIndex = 0; evaluatorIndex < evaluators.size(); evaluatorIndex++) {
            ConstraintEvaluator evaluator = evaluators.get(evaluatorIndex);
            ConstraintDependencyMetadata dependency = dependencyMetadata.get(evaluatorIndex);
            int unitCount = unitCount(dependency);
            RosterScore[] evaluatorCache = new RosterScore[unitCount];
            for (int unitIndex = 0; unitIndex < unitCount; unitIndex++) {
                RosterScore unitScore = evaluateUnit(evaluator, dependency, baseContext, unitIndex);
                evaluatorCache[unitIndex] = unitScore;
                add(total, unitScore, 1L);
            }
            scoreCache.add(evaluatorCache);
        }
        RosterScore partitionedScore = toRosterScore(total);
        if (!partitionedScore.equals(verifiedFullScore)) {
            throw new ScoreMismatchException(
                    "제약별 증분 cache partition 합계가 full score와 다릅니다.",
                    partitionedScore, verifiedFullScore, verifiedInitial);
        }
        this.currentScore = partitionedScore;
    }

    public PlanningProblem problem() {
        return problem;
    }

    public RosterScore currentScore() {
        return currentScore;
    }

    public List<ConstraintDependencyMetadata> dependencyMetadata() {
        return dependencyMetadata;
    }

    public RosterSolution lastVerifiedBest() {
        return lastVerifiedBest;
    }

    /**
     * move 적용 후 snapshot의 영향 셀만 계산하고 cache와 점수를 원자적으로 교체합니다.
     */
    public RosterScore refreshAfterMove(RosterSolution afterMove, MoveImpact impact) {
        Objects.requireNonNull(afterMove, "afterMove");
        Objects.requireNonNull(impact, "impact");
        validateSolutionShape(afterMove);
        RosterSolution evaluationSolution = withScore(afterMove, currentScore);
        ScoreEvaluationContext baseContext = new ScoreEvaluationContext(problem, evaluationSolution);
        List<PendingUpdate> pending = new ArrayList<>();
        long[] total = toLongVector(currentScore);

        for (int evaluatorIndex = 0; evaluatorIndex < evaluators.size(); evaluatorIndex++) {
            ConstraintEvaluator evaluator = evaluators.get(evaluatorIndex);
            ConstraintDependencyMetadata dependency = dependencyMetadata.get(evaluatorIndex);
            ConstraintImpact constraintImpact = impact.forEvaluator(evaluator.evaluatorId());
            if (!dependency.equals(constraintImpact.metadata())) {
                throw new IllegalArgumentException(
                        "MoveImpact metadata가 calculator와 다릅니다: " + evaluator.evaluatorId());
            }
            RosterScore[] evaluatorCache = scoreCache.get(evaluatorIndex);
            for (int unitIndex : constraintImpact.evaluationUnitIndexes()) {
                if (unitIndex < 0 || unitIndex >= evaluatorCache.length) {
                    throw new IllegalArgumentException(
                            "증분 평가 unitIndex 범위를 벗어났습니다: " + unitIndex);
                }
                RosterScore oldScore = evaluatorCache[unitIndex];
                RosterScore newScore = evaluateUnit(evaluator, dependency, baseContext, unitIndex);
                add(total, oldScore, -1L);
                add(total, newScore, 1L);
                pending.add(new PendingUpdate(evaluatorIndex, unitIndex, newScore));
            }
        }

        RosterScore nextScore = toRosterScore(total);
        for (PendingUpdate update : pending) {
            scoreCache.get(update.evaluatorIndex())[update.unitIndex()] = update.score();
        }
        currentScore = nextScore;
        return nextScore;
    }

    /** full 점수와 비교하되 transaction commit 전에는 verified best를 변경하지 않습니다. */
    public RosterSolution verifyAgainstFull(RosterSolution candidate) {
        Objects.requireNonNull(candidate, "candidate");
        validateSolutionShape(candidate);
        RosterScore fullScore = fullScoreCalculator.calculateScore(problem, candidate);
        if (!currentScore.equals(fullScore)) {
            throw new ScoreMismatchException(
                    "full/incremental score가 일치하지 않습니다.",
                    currentScore, fullScore, lastVerifiedBest);
        }
        return withScore(candidate, fullScore);
    }

    /** commit되는 최종 complete candidate만 다시 full 검증한 뒤 verified best로 승격합니다. */
    public RosterSolution verifyAndRememberBest(RosterSolution candidate) {
        RosterSolution verified = verifyAgainstFull(candidate);
        if (lastVerifiedBest == null || verified.score().compareTo(lastVerifiedBest.score()) > 0) {
            lastVerifiedBest = verified;
        }
        return verified;
    }

    public ScoreCacheFingerprint cacheFingerprint() {
        long xor = 0x6A09E667F3BCC909L;
        long sum = 0xBB67AE8584CAA73BL;
        int cell = 0;
        for (int evaluatorIndex = 0; evaluatorIndex < scoreCache.size(); evaluatorIndex++) {
            for (int unitIndex = 0; unitIndex < scoreCache.get(evaluatorIndex).length; unitIndex++) {
                RosterScore score = scoreCache.get(evaluatorIndex)[unitIndex];
                long hash = mix64(((long) evaluatorIndex << 48)
                        ^ ((long) unitIndex << 16)
                        ^ score.hashCode());
                xor ^= hash;
                sum += Long.rotateLeft(hash, cell & 63);
                cell++;
            }
        }
        return new ScoreCacheFingerprint(xor, sum, cell);
    }

    private RosterScore evaluateUnit(
            ConstraintEvaluator evaluator,
            ConstraintDependencyMetadata dependency,
            ScoreEvaluationContext baseContext,
            int unitIndex) {
        ScoreEvaluationContext scoped = dependency.granularity() == EvaluationGranularity.SHIFT
                ? baseContext.forShift(unitIndex)
                : baseContext.forEmployee(unitIndex);
        ContributionCollector collector = new ContributionCollector();
        evaluator.evaluate(scoped, collector);
        return collector.toResult().score();
    }

    private int unitCount(ConstraintDependencyMetadata dependency) {
        return dependency.granularity() == EvaluationGranularity.SHIFT
                ? problem.shiftCount()
                : problem.employeeCount();
    }

    private void validateSolutionShape(RosterSolution solution) {
        if (solution.employeeCount() != problem.employeeCount()
                || solution.shiftCount() != problem.shiftCount()) {
            throw new IllegalArgumentException("RosterSolution과 PlanningProblem의 크기가 다릅니다.");
        }
    }

    private static RosterSolution withScore(RosterSolution source, RosterScore score) {
        return new RosterSolution(source.employeeCount(), source.employeeIndexByShift(), score);
    }

    private static long[] toLongVector(RosterScore score) {
        return new long[] {
                score.hardScore(),
                score.softScore(0),
                score.softScore(1),
                score.softScore(2),
                score.softScore(3)
        };
    }

    private static void add(long[] target, RosterScore score, long multiplier) {
        target[0] = Math.addExact(target[0], Math.multiplyExact(multiplier, score.hardScore()));
        for (int softIndex = 0; softIndex < RosterScore.SOFT_LEVELS; softIndex++) {
            target[softIndex + 1] = Math.addExact(
                    target[softIndex + 1],
                    Math.multiplyExact(multiplier, score.softScore(softIndex)));
        }
    }

    private static RosterScore toRosterScore(long[] values) {
        return RosterScore.of(
                Math.toIntExact(values[0]),
                Math.toIntExact(values[1]),
                Math.toIntExact(values[2]),
                Math.toIntExact(values[3]),
                Math.toIntExact(values[4]));
    }

    private static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private record PendingUpdate(int evaluatorIndex, int unitIndex, RosterScore score) {
    }
}
