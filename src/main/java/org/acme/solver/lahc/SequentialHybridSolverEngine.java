package org.acme.solver.lahc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.acme.solver.alns.AlnsSolverEngine;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveListener;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.core.SolverEngine;
import org.acme.solver.core.TerminationReason;
import org.acme.solver.initial.InitialSolutionBuilder;
import org.acme.solver.initial.InitialSolutionResult;
import org.acme.solver.score.FullScoreCalculator;

/**
 * Change/Swap과 ALNS를 같은 root warm start에서 순차적으로 측정하는 테스트 전용 엔진입니다.
 *
 * <p>각 단계는 독립 {@link SearchState}와 score cache를 새로 만들며, 단계 간에는 full-score가
 * 검증된 immutable snapshot만 warm start로 넘깁니다. 따라서 listener, best, 평가 횟수는 부모가
 * 전역 단조 best와 단계별 합계로 다시 관리하며 두 엔진을 단순히 이어 호출하지 않습니다.</p>
 */
public final class SequentialHybridSolverEngine implements SolverEngine {

    public enum Order {
        OPTA_STYLE_THEN_ALNS,
        ALNS_THEN_OPTA_STYLE
    }

    private final Order order;
    private final SolverEngine optaStyle;
    private final SolverEngine alns;
    private final FullScoreCalculator fullScoreCalculator;
    private final InitialSolutionBuilder initialSolutionBuilder;

    public SequentialHybridSolverEngine(Order order) {
        this(order, new OptaStyleLocalSearchEngine(), new AlnsSolverEngine(), new FullScoreCalculator());
    }

    SequentialHybridSolverEngine(
            Order order,
            SolverEngine optaStyle,
            SolverEngine alns,
            FullScoreCalculator fullScoreCalculator) {
        this.order = Objects.requireNonNull(order, "order");
        this.optaStyle = Objects.requireNonNull(optaStyle, "optaStyle");
        this.alns = Objects.requireNonNull(alns, "alns");
        this.fullScoreCalculator = Objects.requireNonNull(fullScoreCalculator, "fullScoreCalculator");
        this.initialSolutionBuilder = new InitialSolutionBuilder(this.fullScoreCalculator);
    }

    @Override
    public SolveResult<RosterSolution> solve(
            PlanningProblem problem, SolveOptions options, SolveListener listener) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(listener, "listener");
        long startedNanos = System.nanoTime();
        RosterSolution root = rootWarmStart(problem, options);
        if (root == null) {
            return result(null, TerminationReason.INITIAL_SOLUTION_FAILED, 0L, startedNanos, options.randomSeed(), List.of(), 0L, 0L);
        }
        notifySafely(listener, root);

        List<StageSpec> stages = orderedStages();
        List<SequentialHybridMetrics.Stage> stageMetrics = new ArrayList<>();
        RosterSolution globalBest = root;
        long totalEvaluations = 0L;
        long scoreMismatchFailures = 0L;
        long stateCorruptionFailures = 0L;
        TerminationReason finalReason = TerminationReason.COMPLETED;
        long totalBudget = options.maxEvaluations();

        for (int index = 0; index < stages.size(); index++) {
            if (options.cancellationToken().isCancellationRequested()) {
                finalReason = TerminationReason.CANCELLED;
                break;
            }
            long evaluationBudget = splitBudget(totalBudget, index, stages.size());
            if (totalBudget != SolveOptions.UNLIMITED && evaluationBudget == 0L) {
                continue;
            }
            StageSpec stage = stages.get(index);
            long stageSeed = deriveSeed(options.randomSeed(), stage.engineId());
            long stageStartedNanos = System.nanoTime();
            RosterSolution input = globalBest;
            SolveOptions stageOptions = stageOptions(options, input, stageSeed, evaluationBudget, startedNanos, index);
            final RosterSolution[] listenerBest = { globalBest };
            SolveResult<RosterSolution> stageResult = stage.engine().solve(problem, stageOptions, candidate -> {
                RosterSolution verified = verify(problem, candidate);
                if (verified.score().compareTo(listenerBest[0].score()) > 0) {
                    listenerBest[0] = verified;
                    notifySafely(listener, verified);
                }
            });
            totalEvaluations += stageResult.evaluationCount();
            RosterSolution stageBest = stageResult.bestSolution() == null ? input : verify(problem, stageResult.bestSolution());
            if (stageBest.score().compareTo(globalBest.score()) > 0) {
                globalBest = stageBest;
                if (stageBest.score().compareTo(listenerBest[0].score()) > 0) {
                    notifySafely(listener, stageBest);
                }
            }
            if (stageResult.terminationReason() == TerminationReason.SCORE_MISMATCH) {
                scoreMismatchFailures++;
            }
            if (stageResult.terminationReason() == TerminationReason.STATE_CORRUPTION) {
                stateCorruptionFailures++;
            }
            stageMetrics.add(new SequentialHybridMetrics.Stage(
                    stage.engineId(), stageSeed, evaluationBudget, stageResult.evaluationCount(),
                    Duration.ofNanos(System.nanoTime() - stageStartedNanos).toMillis(), input.score(),
                    stageBest.score(), stageResult.terminationReason()));
            finalReason = stageResult.terminationReason();
            if (isFatal(finalReason) || finalReason == TerminationReason.CANCELLED
                    || finalReason == TerminationReason.DEADLINE_REACHED) {
                break;
            }
        }
        if (!globalBest.score().isFeasible() && !isFatal(finalReason)) {
            finalReason = TerminationReason.NO_FEASIBLE_SOLUTION;
        }
        return result(globalBest, finalReason, totalEvaluations, startedNanos, options.randomSeed(),
                stageMetrics, scoreMismatchFailures, stateCorruptionFailures);
    }

    private RosterSolution rootWarmStart(PlanningProblem problem, SolveOptions options) {
        if (options.warmStart().isPresent()) {
            RosterSolution warmStart = options.warmStart().orElseThrow();
            if (warmStart.employeeCount() != problem.employeeCount() || warmStart.shiftCount() != problem.shiftCount()) {
                return null;
            }
            for (int shiftIndex : problem.pinnedShiftIndexes()) {
                if (warmStart.employeeIndex(shiftIndex) != problem.initialEmployeeIndex(shiftIndex)) {
                    return null;
                }
            }
            return verify(problem, warmStart);
        }
        InitialSolutionResult initial = initialSolutionBuilder.build(problem, options.cancellationToken()::isCancellationRequested);
        return initial.succeeded() ? verify(problem, initial.solution()) : null;
    }

    private RosterSolution verify(PlanningProblem problem, RosterSolution candidate) {
        RosterScore score = fullScoreCalculator.calculateScore(problem, candidate);
        return new RosterSolution(problem.employeeCount(), candidate.employeeIndexByShift(), score);
    }

    private SolveOptions stageOptions(
            SolveOptions source, RosterSolution warmStart, long seed, long evaluationBudget,
            long startedNanos, int stageIndex) {
        SolveOptions.Builder builder = source.toBuilder()
                .warmStart(warmStart)
                .randomSeed(seed)
                .spentLimit(null);
        if (source.maxEvaluations() != SolveOptions.UNLIMITED) {
            builder.maxEvaluations(evaluationBudget);
        }
        if (source.spentLimit().isPresent()) {
            long overallDeadline = saturatedAdd(startedNanos, source.spentLimit().orElseThrow().toNanos());
            long midpoint = startedNanos + Math.max(1L, (overallDeadline - startedNanos) / 2L);
            builder.deadlineNanos(stageIndex == 0 ? midpoint : overallDeadline);
        }
        return builder.build();
    }

    private List<StageSpec> orderedStages() {
        StageSpec opta = new StageSpec("OPTA_STYLE_CHANGE_SWAP", optaStyle);
        StageSpec alnsStage = new StageSpec("ALNS", alns);
        return order == Order.OPTA_STYLE_THEN_ALNS ? List.of(opta, alnsStage) : List.of(alnsStage, opta);
    }

    private static long splitBudget(long total, int stageIndex, int stageCount) {
        if (total == SolveOptions.UNLIMITED) {
            return SolveOptions.UNLIMITED;
        }
        long first = (total + stageCount - 1L) / stageCount;
        return stageIndex == 0 ? first : total - first;
    }

    private static boolean isFatal(TerminationReason reason) {
        return reason == TerminationReason.SCORE_MISMATCH || reason == TerminationReason.STATE_CORRUPTION
                || reason == TerminationReason.INITIAL_SOLUTION_FAILED;
    }

    private static long deriveSeed(long rootSeed, String engineId) {
        long mixed = rootSeed ^ ((long) engineId.hashCode() << 32) ^ engineId.hashCode();
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return mixed ^ (mixed >>> 31);
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static void notifySafely(SolveListener listener, RosterSolution best) {
        try {
            listener.onBestSolution(best);
        } catch (RuntimeException ignored) {
            // Listener는 탐색 상태와 독립입니다.
        }
    }

    private SolveResult<RosterSolution> result(
            RosterSolution solution, TerminationReason reason, long evaluations, long startedNanos,
            long seed, List<SequentialHybridMetrics.Stage> stages, long scoreMismatchFailures,
            long stateCorruptionFailures) {
        SequentialHybridMetrics metrics = new SequentialHybridMetrics(
                order.name(), seed, evaluations, stages, scoreMismatchFailures,
                stateCorruptionFailures, reason);
        return new SolveResult<>(solution, solution == null ? null : solution.score(), reason,
                stages.size(), evaluations, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis(), seed, metrics);
    }

    private record StageSpec(String engineId, SolverEngine engine) {
    }
}
