package org.acme.solver.lahc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;

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
import org.acme.solver.move.Move;
import org.acme.solver.move.MoveTransaction;
import org.acme.solver.move.SearchState;
import org.acme.solver.move.StateCorruptionException;
import org.acme.solver.score.FullScoreCalculator;
import org.acme.solver.score.IncrementalScoreCalculator;
import org.acme.solver.score.ScoreMismatchException;

/**
 * feasible warm start의 hard, soft[0], soft[1]을 절대 악화시키지 않고 soft[2]만 올리는
 * Change/Swap intensification입니다. production selector에는 등록하지 않는 benchmark 전용 후보입니다.
 */
public final class FairnessRestrictedLocalSearchEngine implements SolverEngine {

    static final long DEFAULT_INITIAL_FEASIBILITY_EVALUATIONS = 10_000L;

    private final FullScoreCalculator fullScoreCalculator;
    private final InitialSolutionBuilder initialSolutionBuilder;
    private final LahcMoveSelector moveSelector;

    public FairnessRestrictedLocalSearchEngine() {
        this(new FullScoreCalculator(), new OptaStyleMoveSelector());
    }

    /** fairness hotspot 재배정 후보를 쓰는 test-only intensification lane입니다. */
    public static FairnessRestrictedLocalSearchEngine hotspotGuidedProtectedReassign() {
        return new FairnessRestrictedLocalSearchEngine(
                new FullScoreCalculator(), new FairnessHotspotGuidedProtectedReassignSelector());
    }

    FairnessRestrictedLocalSearchEngine(FullScoreCalculator fullScoreCalculator, LahcMoveSelector moveSelector) {
        this.fullScoreCalculator = Objects.requireNonNull(fullScoreCalculator, "fullScoreCalculator");
        this.initialSolutionBuilder = new InitialSolutionBuilder(this.fullScoreCalculator);
        this.moveSelector = Objects.requireNonNull(moveSelector, "moveSelector");
    }

    @Override
    public SolveResult<RosterSolution> solve(
            PlanningProblem problem, SolveOptions options, SolveListener listener) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(listener, "listener");
        requireTermination(options);
        long startedNanos = System.nanoTime();
        long deadline = deadline(startedNanos, options);
        RosterSolution initial = initial(problem, options);
        if (initial == null) {
            return result(null, TerminationReason.INITIAL_SOLUTION_FAILED, 0L, 0L, startedNanos,
                    options.randomSeed(), 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                    FairnessSelectorMetrics.empty(), List.of());
        }
        long initialFeasibilityEvaluations = 0L;
        if (!initial.score().isFeasible()) {
            SolveResult<RosterSolution> bootstrap = new LahcSolverEngine(
                    initialSolutionBuilder, fullScoreCalculator, new SeededMoveSelector(), 100).solve(
                            problem, SolveOptions.builder().deadlineNanos(deadline)
                                    .maxEvaluations(DEFAULT_INITIAL_FEASIBILITY_EVALUATIONS)
                                    .randomSeed(deriveBootstrapSeed(options.randomSeed())).warmStart(initial)
                                    .cancellationToken(options.cancellationToken()).build(), ignored -> { });
            initialFeasibilityEvaluations = bootstrap.evaluationCount();
            if (bootstrap.bestSolution() != null) {
                initial = verified(problem, bootstrap.bestSolution());
            }
            if (bootstrap.terminationReason() == TerminationReason.SCORE_MISMATCH
                    || bootstrap.terminationReason() == TerminationReason.STATE_CORRUPTION) {
                return result(initial, bootstrap.terminationReason(), 0L, 0L, startedNanos, options.randomSeed(),
                        0L, 0L, 0L, 0L, 0L, 0L, 1L, 0L, 0L, initialFeasibilityEvaluations,
                        FairnessSelectorMetrics.empty(), List.of());
            }
            if (!initial.score().isFeasible()) {
                return result(initial, TerminationReason.NO_FEASIBLE_SOLUTION, 0L, 0L, startedNanos,
                        options.randomSeed(), 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                        initialFeasibilityEvaluations, FairnessSelectorMetrics.empty(), List.of());
            }
        }
        notifySafely(listener, initial);
        SearchState state = new SearchState(problem, initial);
        IncrementalScoreCalculator incremental = new IncrementalScoreCalculator(problem, initial, fullScoreCalculator);
        RosterSolution best = initial;
        SplittableRandom random = new SplittableRandom(options.randomSeed());
        long evaluations = 0L;
        long accepted = 0L;
        long rejected = 0L;
        long changes = 0L;
        long swaps = 0L;
        long acceptedChanges = 0L;
        long acceptedSwaps = 0L;
        long mismatches = 0L;
        long corruptions = 0L;
        long fullVerifications = 0L;
        List<FairnessRestrictedLocalSearchMetrics.BestImprovement> improvements = new ArrayList<>();
        TerminationReason reason = null;

        while (reason == null) {
            reason = requestedTermination(options, deadline);
            if (reason != null || reached(evaluations, options.maxEvaluations())
                    || reached(accepted, options.maxIterations())) {
                if (reason == null) {
                    reason = reached(evaluations, options.maxEvaluations())
                            ? TerminationReason.MAX_EVALUATIONS_REACHED : TerminationReason.MAX_ITERATIONS_REACHED;
                }
                break;
            }
            Optional<Move> selected = moveSelector.select(problem, state, random);
            if (selected.isEmpty()) {
                reason = TerminationReason.CONVERGED;
                break;
            }
            Move move = selected.orElseThrow();
            if (!"REASSIGN".equals(move.moveType()) && !"SWAP".equals(move.moveType())) {
                throw new IllegalStateException("fairness restricted lane은 Change/Swap만 허용합니다: " + move.moveType());
            }
            RosterScore current = state.score();
            try (MoveTransaction transaction = MoveTransaction.open(state, incremental)) {
                transaction.apply(move);
                RosterScore candidate = transaction.verifyCandidate().score();
                fullVerifications++;
                evaluations++;
                boolean change = "REASSIGN".equals(move.moveType());
                if (change) {
                    changes++;
                } else {
                    swaps++;
                }
                if (!isAllowedFairnessImprovement(current, candidate)) {
                    transaction.rollback();
                    rejected++;
                    continue;
                }
                transaction.commit();
                accepted++;
                if (change) {
                    acceptedChanges++;
                } else {
                    acceptedSwaps++;
                }
                RosterSolution improved = state.snapshot();
                best = improved;
                improvements.add(new FairnessRestrictedLocalSearchMetrics.BestImprovement(
                        evaluations, improved.score(), move.moveType()));
                notifySafely(listener, improved);
            } catch (ScoreMismatchException mismatch) {
                mismatches++;
                reason = TerminationReason.SCORE_MISMATCH;
            } catch (StateCorruptionException corruption) {
                corruptions++;
                reason = TerminationReason.STATE_CORRUPTION;
            }
        }
        if (reason == null) {
            reason = TerminationReason.COMPLETED;
        }
        RosterScore verified = fullScoreCalculator.calculateScore(problem, best);
        fullVerifications++;
        if (!verified.equals(best.score())) {
            mismatches++;
            reason = TerminationReason.SCORE_MISMATCH;
        }
        return result(best, reason, accepted, evaluations, startedNanos, options.randomSeed(), accepted,
                rejected, changes, swaps, acceptedChanges, acceptedSwaps, mismatches, corruptions,
                fullVerifications, initialFeasibilityEvaluations, selectorMetrics(), improvements);
    }

    /**
     * hard·soft[0](undesired) 비열화 없이 soft[1](fairness)이 엄격히 좋아질 때만 true입니다.
     * soft[2](desired)는 보호하지 않습니다.
     */
    static boolean isAllowedFairnessImprovement(RosterScore current, RosterScore candidate) {
        return candidate.hardScore() >= current.hardScore()
                && candidate.softScore(0) >= current.softScore(0)
                && candidate.softScore(1) > current.softScore(1);
    }

    private RosterSolution initial(PlanningProblem problem, SolveOptions options) {
        if (options.warmStart().isPresent()) {
            RosterSolution warm = options.warmStart().orElseThrow();
            if (warm.employeeCount() != problem.employeeCount() || warm.shiftCount() != problem.shiftCount()) {
                return null;
            }
            return verified(problem, warm);
        }
        InitialSolutionResult result = initialSolutionBuilder.build(problem, options.cancellationToken()::isCancellationRequested);
        return result.succeeded() ? verified(problem, result.solution()) : null;
    }

    private RosterSolution verified(PlanningProblem problem, RosterSolution solution) {
        return new RosterSolution(problem.employeeCount(), solution.employeeIndexByShift(),
                fullScoreCalculator.calculateScore(problem, solution));
    }

    private static void requireTermination(SolveOptions options) {
        if (options.spentLimit().isEmpty() && !options.hasDeadline()
                && options.maxEvaluations() == SolveOptions.UNLIMITED
                && options.maxIterations() == SolveOptions.UNLIMITED) {
            throw new IllegalArgumentException("fairness restricted local search에는 종료 조건이 필요합니다.");
        }
    }

    private static long deadline(long startedNanos, SolveOptions options) {
        if (options.spentLimit().isEmpty()) {
            return options.deadlineNanos();
        }
        long fromSpent = saturatedAdd(startedNanos, options.spentLimit().orElseThrow().toNanos());
        return options.hasDeadline() && options.deadlineNanos() - startedNanos < fromSpent - startedNanos
                ? options.deadlineNanos() : fromSpent;
    }

    private static TerminationReason requestedTermination(SolveOptions options, long deadline) {
        if (options.cancellationToken().isCancellationRequested()) {
            return TerminationReason.CANCELLED;
        }
        return deadline != SolveOptions.NO_DEADLINE && System.nanoTime() - deadline >= 0L
                ? TerminationReason.DEADLINE_REACHED : null;
    }

    private static boolean reached(long count, long limit) {
        return limit != SolveOptions.UNLIMITED && count >= limit;
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long deriveBootstrapSeed(long searchSeed) {
        long value = searchSeed ^ 0x464149524E455353L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static void notifySafely(SolveListener listener, RosterSolution solution) {
        try {
            listener.onBestSolution(solution);
        } catch (RuntimeException ignored) {
            // Listener 실패는 verified best를 바꾸지 않습니다.
        }
    }

    private FairnessSelectorMetrics selectorMetrics() {
        return moveSelector instanceof FairnessHotspotGuidedProtectedReassignSelector selector
                ? selector.metrics() : FairnessSelectorMetrics.empty();
    }

    private static SolveResult<RosterSolution> result(
            RosterSolution solution, TerminationReason reason, long iterations, long evaluations,
            long startedNanos, long seed, long accepted, long rejected, long changes, long swaps,
            long acceptedChanges, long acceptedSwaps, long mismatches, long corruptions,
            long fullVerifications, long initialFeasibilityEvaluations,
            FairnessSelectorMetrics selectorMetrics,
            List<FairnessRestrictedLocalSearchMetrics.BestImprovement> improvements) {
        FairnessRestrictedLocalSearchMetrics metrics = new FairnessRestrictedLocalSearchMetrics(
                evaluations, accepted, rejected, changes, swaps, acceptedChanges, acceptedSwaps,
                mismatches, corruptions, fullVerifications, initialFeasibilityEvaluations, selectorMetrics,
                improvements, reason);
        return new SolveResult<>(solution, solution == null ? null : solution.score(), reason, iterations,
                evaluations, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis(), seed, metrics);
    }
}
