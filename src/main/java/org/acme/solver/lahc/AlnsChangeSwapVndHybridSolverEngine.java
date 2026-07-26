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
import org.acme.solver.core.SolveMetrics;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.core.SolverEngine;
import org.acme.solver.core.TerminationReason;
import org.acme.solver.initial.InitialSolutionBuilder;
import org.acme.solver.initial.InitialSolutionResult;
import org.acme.solver.score.FullScoreCalculator;

/**
 * ALNS diversification 뒤 Change/Swap ordered VND를 실행하는 benchmark 전용 hybrid입니다.
 *
 * <p>고정 평가 프로필에서는 ALNS 80%, Change/Swap VND 20%를 예약한다. protected fairness를
 * 포함한 mode는 VND 15%, fairness 5%를 예약한다. 앞 단계가 일찍 끝내면 남은 평가 예산은
 * 다음 단계로 넘기되, fairness 예약분은 VND가 침범하지 못한다. wall-clock도 같은 비율의
 * 절대 deadline을 사용하며, 단계 경계에는 full-score 검증 immutable snapshot만 전달한다.</p>
 */
public final class AlnsChangeSwapVndHybridSolverEngine implements SolverEngine {

    public enum Mode {
        ALNS_THEN_CHANGE_SWAP,
        ALNS_THEN_ORDERED_VND_WITH_PROTECTED_FAIRNESS
    }

    private static final int ALNS_PERCENT = 80;
    private static final int CHANGE_SWAP_PERCENT = 20;
    private static final int VND_WITH_FAIRNESS_PERCENT = 15;
    private static final int FAIRNESS_PERCENT = 5;

    private final Mode mode;
    private final SolverEngine alns;
    private final SolverEngine vnd;
    private final SolverEngine fairness;
    private final FullScoreCalculator fullScoreCalculator;
    private final InitialSolutionBuilder initialSolutionBuilder;

    public AlnsChangeSwapVndHybridSolverEngine(Mode mode) {
        this(mode, new AlnsSolverEngine(), new OrderedVndLocalSearchEngine(),
                FairnessRestrictedLocalSearchEngine.hotspotGuidedProtectedReassign(), new FullScoreCalculator());
    }

    AlnsChangeSwapVndHybridSolverEngine(
            Mode mode, SolverEngine alns, SolverEngine vnd, SolverEngine fairness,
            FullScoreCalculator fullScoreCalculator) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.alns = Objects.requireNonNull(alns, "alns");
        this.vnd = Objects.requireNonNull(vnd, "vnd");
        this.fairness = Objects.requireNonNull(fairness, "fairness");
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
            return result(null, TerminationReason.INITIAL_SOLUTION_FAILED, 0L, startedNanos,
                    options.randomSeed(), List.of(), 0L, 0L);
        }
        notifySafely(listener, root);
        List<StageSpec> specs = stages();
        List<AlnsChangeSwapVndHybridMetrics.Stage> stageMetrics = new ArrayList<>();
        RosterSolution globalBest = root;
        long evaluations = 0L;
        long mismatchFailures = 0L;
        long corruptionFailures = 0L;
        TerminationReason finalReason = TerminationReason.COMPLETED;
        long totalBudget = options.maxEvaluations();
        long overallDeadline = overallDeadline(startedNanos, options);

        for (int index = 0; index < specs.size(); index++) {
            if (options.cancellationToken().isCancellationRequested()) {
                finalReason = TerminationReason.CANCELLED;
                break;
            }
            StageSpec spec = specs.get(index);
            long reserved = reserve(totalBudget, spec.reservedPercent());
            long fairnessReserve = reserveForFollowingFairness(totalBudget, index, specs);
            long available = totalBudget == SolveOptions.UNLIMITED
                    ? SolveOptions.UNLIMITED
                    : Math.max(0L, totalBudget - evaluations - fairnessReserve);
            if (totalBudget != SolveOptions.UNLIMITED && available == 0L) {
                continue;
            }
            long stageDeadline = stageDeadline(startedNanos, overallDeadline, spec.deadlinePercent());
            if (stageDeadline != SolveOptions.NO_DEADLINE && System.nanoTime() - stageDeadline >= 0L) {
                finalReason = TerminationReason.DEADLINE_REACHED;
                break;
            }
            long seed = deriveSeed(options.randomSeed(), spec.stageId());
            RosterSolution input = globalBest;
            long stageStarted = System.nanoTime();
            long reservedWallMillis = reservedWallMillis(startedNanos, overallDeadline, spec.reservedPercent());
            long availableWallMillis = availableWallMillis(stageStarted, stageDeadline);
            final RosterSolution[] listenerBest = { globalBest };
            SolveResult<RosterSolution> stage = spec.engine().solve(problem,
                    stageOptions(options, input, seed, available, stageDeadline), candidate -> {
                        RosterSolution verified = verify(problem, candidate);
                        if (verified.score().compareTo(listenerBest[0].score()) > 0) {
                            listenerBest[0] = verified;
                            notifySafely(listener, verified);
                        }
                    });
            evaluations += stage.evaluationCount();
            RosterSolution stageBest = stage.bestSolution() == null ? input : verify(problem, stage.bestSolution());
            if (stageBest.score().compareTo(globalBest.score()) > 0) {
                globalBest = stageBest;
                if (stageBest.score().compareTo(listenerBest[0].score()) > 0) {
                    notifySafely(listener, stageBest);
                }
            }
            if (stage.terminationReason() == TerminationReason.SCORE_MISMATCH) {
                mismatchFailures++;
            }
            if (stage.terminationReason() == TerminationReason.STATE_CORRUPTION) {
                corruptionFailures++;
            }
            long unused = available == SolveOptions.UNLIMITED ? SolveOptions.UNLIMITED
                    : Math.max(0L, available - stage.evaluationCount());
            long unusedWallMillis = availableWallMillis(System.nanoTime(), stageDeadline);
            stageMetrics.add(new AlnsChangeSwapVndHybridMetrics.Stage(
                    spec.stageId(), seed, reserved, available, stage.evaluationCount(),
                    Duration.ofNanos(System.nanoTime() - stageStarted).toMillis(), unused,
                    reservedWallMillis, availableWallMillis, unusedWallMillis,
                    input.score(), stageBest.score(), stage.terminationReason(), stage.metrics()));
            finalReason = stage.terminationReason();
            if (fatal(finalReason) || finalReason == TerminationReason.CANCELLED
                    || (finalReason == TerminationReason.DEADLINE_REACHED && index == specs.size() - 1)) {
                break;
            }
            // stage deadline은 전체 deadline이 아니라 다음 phase에 예약한 시간을 지키는 경계입니다.
            // 따라서 ALNS/VND가 자체 stage deadline으로 끝나도 전체 deadline 전에는 다음 stage를 실행합니다.
        }
        RosterScore finalScore = fullScoreCalculator.calculateScore(problem, globalBest);
        if (!finalScore.equals(globalBest.score())) {
            mismatchFailures++;
            finalReason = TerminationReason.SCORE_MISMATCH;
        }
        if (!globalBest.score().isFeasible() && !fatal(finalReason)) {
            finalReason = TerminationReason.NO_FEASIBLE_SOLUTION;
        }
        return result(globalBest, finalReason, evaluations, startedNanos, options.randomSeed(),
                stageMetrics, mismatchFailures, corruptionFailures);
    }

    private List<StageSpec> stages() {
        if (mode == Mode.ALNS_THEN_CHANGE_SWAP) {
            return List.of(new StageSpec("ALNS", alns, ALNS_PERCENT, ALNS_PERCENT),
                    new StageSpec("ORDERED_CHANGE_SWAP_VND", vnd, CHANGE_SWAP_PERCENT, 100));
        }
        return List.of(new StageSpec("ALNS", alns, ALNS_PERCENT, ALNS_PERCENT),
                new StageSpec("ORDERED_CHANGE_SWAP_VND", vnd, VND_WITH_FAIRNESS_PERCENT,
                        ALNS_PERCENT + VND_WITH_FAIRNESS_PERCENT),
                new StageSpec("PROTECTED_FAIRNESS_REASSIGN", fairness, FAIRNESS_PERCENT, 100));
    }

    private RosterSolution rootWarmStart(PlanningProblem problem, SolveOptions options) {
        if (options.warmStart().isPresent()) {
            RosterSolution warm = options.warmStart().orElseThrow();
            if (warm.employeeCount() != problem.employeeCount() || warm.shiftCount() != problem.shiftCount()) {
                return null;
            }
            for (int shiftIndex : problem.pinnedShiftIndexes()) {
                if (warm.employeeIndex(shiftIndex) != problem.initialEmployeeIndex(shiftIndex)) {
                    return null;
                }
            }
            return verify(problem, warm);
        }
        InitialSolutionResult built = initialSolutionBuilder.build(problem, options.cancellationToken()::isCancellationRequested);
        return built.succeeded() ? verify(problem, built.solution()) : null;
    }

    private SolveOptions stageOptions(
            SolveOptions source, RosterSolution warmStart, long seed, long evaluationBudget, long deadline) {
        SolveOptions.Builder builder = source.toBuilder().warmStart(warmStart).randomSeed(seed).spentLimit(null);
        if (source.maxEvaluations() != SolveOptions.UNLIMITED) {
            builder.maxEvaluations(evaluationBudget);
        }
        if (deadline != SolveOptions.NO_DEADLINE) {
            builder.deadlineNanos(deadline);
        }
        return builder.build();
    }

    private RosterSolution verify(PlanningProblem problem, RosterSolution solution) {
        return new RosterSolution(problem.employeeCount(), solution.employeeIndexByShift(),
                fullScoreCalculator.calculateScore(problem, solution));
    }

    private static long reserve(long totalBudget, int percent) {
        return totalBudget == SolveOptions.UNLIMITED ? SolveOptions.UNLIMITED : totalBudget * percent / 100L;
    }

    private static long reserveForFollowingFairness(long totalBudget, int index, List<StageSpec> stages) {
        if (totalBudget == SolveOptions.UNLIMITED) {
            return 0L;
        }
        long reserve = 0L;
        for (int next = index + 1; next < stages.size(); next++) {
            reserve += reserve(totalBudget, stages.get(next).reservedPercent());
        }
        return reserve;
    }

    private static long overallDeadline(long startedNanos, SolveOptions options) {
        long deadline = options.deadlineNanos();
        if (options.spentLimit().isPresent()) {
            long fromSpent = saturatedAdd(startedNanos, options.spentLimit().orElseThrow().toNanos());
            deadline = deadline == SolveOptions.NO_DEADLINE || fromSpent - startedNanos < deadline - startedNanos
                    ? fromSpent : deadline;
        }
        return deadline;
    }

    private static long stageDeadline(long startedNanos, long overallDeadline, int percent) {
        if (overallDeadline == SolveOptions.NO_DEADLINE) {
            return SolveOptions.NO_DEADLINE;
        }
        long duration = overallDeadline - startedNanos;
        return saturatedAdd(startedNanos, Math.max(1L, duration * percent / 100L));
    }

    private static long reservedWallMillis(long startedNanos, long overallDeadline, int percent) {
        if (overallDeadline == SolveOptions.NO_DEADLINE) {
            return SolveOptions.NO_DEADLINE;
        }
        return Duration.ofNanos(Math.max(0L, (overallDeadline - startedNanos) * percent / 100L)).toMillis();
    }

    private static long availableWallMillis(long fromNanos, long deadlineNanos) {
        if (deadlineNanos == SolveOptions.NO_DEADLINE) {
            return SolveOptions.NO_DEADLINE;
        }
        return Duration.ofNanos(Math.max(0L, deadlineNanos - fromNanos)).toMillis();
    }

    private static boolean fatal(TerminationReason reason) {
        return reason == TerminationReason.SCORE_MISMATCH || reason == TerminationReason.STATE_CORRUPTION
                || reason == TerminationReason.INITIAL_SOLUTION_FAILED;
    }

    private static long deriveSeed(long rootSeed, String stageId) {
        long value = rootSeed ^ ((long) stageId.hashCode() << 32) ^ stageId.hashCode();
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static void notifySafely(SolveListener listener, RosterSolution solution) {
        try {
            listener.onBestSolution(solution);
        } catch (RuntimeException ignored) {
            // listener 예외는 stage state에 영향을 주지 않습니다.
        }
    }

    private SolveResult<RosterSolution> result(
            RosterSolution solution, TerminationReason reason, long evaluations, long startedNanos,
            long seed, List<AlnsChangeSwapVndHybridMetrics.Stage> stages,
            long mismatchFailures, long corruptionFailures) {
        AlnsChangeSwapVndHybridMetrics metrics = new AlnsChangeSwapVndHybridMetrics(
                mode.name(), seed, evaluations, stages, mismatchFailures, corruptionFailures, reason);
        return new SolveResult<>(solution, solution == null ? null : solution.score(), reason,
                stages.size(), evaluations, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis(), seed, metrics);
    }

    private record StageSpec(String stageId, SolverEngine engine, int reservedPercent, int deadlinePercent) {
    }
}
