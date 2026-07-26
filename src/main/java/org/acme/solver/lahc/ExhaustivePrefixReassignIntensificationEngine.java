package org.acme.solver.lahc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
import org.acme.solver.move.MoveTransaction;
import org.acme.solver.move.ReassignMove;
import org.acme.solver.move.SearchState;
import org.acme.solver.move.StateCorruptionException;
import org.acme.solver.score.FullScoreCalculator;
import org.acme.solver.score.IncrementalScoreCalculator;
import org.acme.solver.score.ScoreMismatchException;

/**
 * 한 current state에서 stable reassign 공간을 bounded exhaustive하게 훑는 test-only intensification입니다.
 *
 * <p>새 move나 추측성 delta를 도입하지 않습니다. 모든 후보는 기존 ReassignMove를 transaction으로
 * 적용한 뒤 full score를 검증하고 엄격한 사전식 개선일 때만 commit합니다.</p>
 */
public final class ExhaustivePrefixReassignIntensificationEngine implements SolverEngine {

    public static final int DEFAULT_CANDIDATE_LIMIT_PER_PASS = 5_400;

    private final FullScoreCalculator full;
    private final InitialSolutionBuilder initialBuilder;
    private final int candidateLimitPerPass;
    private final PreceptorPrefixGuidedProtectedReassignSelector selector;

    public ExhaustivePrefixReassignIntensificationEngine() {
        this(new FullScoreCalculator(), DEFAULT_CANDIDATE_LIMIT_PER_PASS);
    }

    public ExhaustivePrefixReassignIntensificationEngine(int candidateLimitPerPass) {
        this(new FullScoreCalculator(), candidateLimitPerPass);
    }

    ExhaustivePrefixReassignIntensificationEngine(FullScoreCalculator full, int candidateLimitPerPass) {
        this.full = Objects.requireNonNull(full, "full");
        this.initialBuilder = new InitialSolutionBuilder(full);
        if (candidateLimitPerPass < 1) {
            throw new IllegalArgumentException("candidateLimitPerPass는 1 이상이어야 합니다.");
        }
        this.candidateLimitPerPass = candidateLimitPerPass;
        this.selector = new PreceptorPrefixGuidedProtectedReassignSelector(full);
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
            return result(null, TerminationReason.INITIAL_SOLUTION_FAILED, startedNanos,
                    options.randomSeed(), new Counters(), List.of());
        }
        notifySafely(listener, initial);
        SearchState state = new SearchState(problem, initial);
        IncrementalScoreCalculator incremental = new IncrementalScoreCalculator(problem, initial, full);
        Counters counters = new Counters();
        List<ExhaustivePrefixReassignIntensificationMetrics.BestImprovement> improvements = new ArrayList<>();
        RosterSolution best = initial;
        TerminationReason reason = null;

        while (reason == null) {
            reason = requestedTermination(options, deadline);
            if (reason != null) {
                break;
            }
            if (reached(counters.evaluated, options.maxEvaluations())) {
                reason = TerminationReason.MAX_EVALUATIONS_REACHED;
                break;
            }
            if (reached(counters.accepted, options.maxIterations())) {
                reason = TerminationReason.MAX_ITERATIONS_REACHED;
                break;
            }
            Scan scan = scan(problem, state, incremental, options, deadline, counters);
            if (scan.reason() != null) {
                reason = scan.reason();
                break;
            }
            if (!scan.improved()) {
                reason = TerminationReason.CONVERGED;
                break;
            }
            best = state.snapshot();
            improvements.add(new ExhaustivePrefixReassignIntensificationMetrics.BestImprovement(
                    counters.evaluated, elapsedMillis(startedNanos), best.score()));
            notifySafely(listener, best);
        }

        RosterScore verified = full.calculateScore(problem, best);
        counters.fullVerifications++;
        if (!verified.equals(best.score())) {
            counters.mismatches++;
            reason = TerminationReason.SCORE_MISMATCH;
        }
        return result(best, reason == null ? TerminationReason.COMPLETED : reason, startedNanos,
                options.randomSeed(), counters, improvements);
    }

    private Scan scan(
            PlanningProblem problem,
            SearchState state,
            IncrementalScoreCalculator incremental,
            SolveOptions options,
            long deadline,
            Counters counters) {
        List<PreceptorPrefixGuidedProtectedReassignSelector.Candidate> candidates =
                selector.candidates(problem, state);
        counters.generated += candidates.size();
        long limit = Math.min(candidates.size(), candidateLimitPerPass);
        RosterScore baseline = state.score();
        for (int position = 0; position < limit; position++) {
            TerminationReason requested = requestedTermination(options, deadline);
            if (requested != null) {
                return new Scan(false, requested);
            }
            if (reached(counters.evaluated, options.maxEvaluations())) {
                return new Scan(false, TerminationReason.MAX_EVALUATIONS_REACHED);
            }
            PreceptorPrefixGuidedProtectedReassignSelector.Candidate selected = candidates.get(position);
            try (MoveTransaction transaction = MoveTransaction.open(state, incremental)) {
                transaction.apply(ReassignMove.create(
                        problem, state, selected.shiftIndex(), selected.targetEmployee()));
                RosterScore candidate = transaction.verifyCandidate().score();
                counters.fullVerifications++;
                counters.evaluated++;
                if (candidate.compareTo(baseline) <= 0) {
                    transaction.rollback();
                    counters.rejected++;
                    continue;
                }
                transaction.commit();
                counters.accepted++;
                return Scan.IMPROVEMENT;
            } catch (ScoreMismatchException mismatch) {
                counters.mismatches++;
                return new Scan(false, TerminationReason.SCORE_MISMATCH);
            } catch (StateCorruptionException corruption) {
                counters.corruptions++;
                return new Scan(false, TerminationReason.STATE_CORRUPTION);
            }
        }
        return Scan.NO_IMPROVEMENT;
    }

    private RosterSolution initial(PlanningProblem problem, SolveOptions options) {
        if (options.warmStart().isPresent()) {
            RosterSolution warm = options.warmStart().orElseThrow();
            if (warm.employeeCount() != problem.employeeCount()
                    || warm.shiftCount() != problem.shiftCount()) {
                return null;
            }
            for (int pinned : problem.pinnedShiftIndexes()) {
                if (warm.employeeIndex(pinned) != problem.initialEmployeeIndex(pinned)) {
                    return null;
                }
            }
            return verified(problem, warm);
        }
        InitialSolutionResult built =
                initialBuilder.build(problem, options.cancellationToken()::isCancellationRequested);
        return built.succeeded() ? verified(problem, built.solution()) : null;
    }

    private RosterSolution verified(PlanningProblem problem, RosterSolution solution) {
        return new RosterSolution(problem.employeeCount(), solution.employeeIndexByShift(),
                full.calculateScore(problem, solution));
    }

    private static void requireTermination(SolveOptions options) {
        if (options.spentLimit().isEmpty() && !options.hasDeadline()
                && options.maxEvaluations() == SolveOptions.UNLIMITED
                && options.maxIterations() == SolveOptions.UNLIMITED) {
            throw new IllegalArgumentException("prefix reassign intensification에는 종료 조건이 필요합니다.");
        }
    }

    private static long deadline(long startedNanos, SolveOptions options) {
        if (options.spentLimit().isEmpty()) {
            return options.deadlineNanos();
        }
        long spentDeadline = saturatedAdd(startedNanos, options.spentLimit().orElseThrow().toNanos());
        return options.hasDeadline() && options.deadlineNanos() - startedNanos < spentDeadline - startedNanos
                ? options.deadlineNanos() : spentDeadline;
    }

    private static TerminationReason requestedTermination(SolveOptions options, long deadline) {
        if (options.cancellationToken().isCancellationRequested()) {
            return TerminationReason.CANCELLED;
        }
        return deadline != SolveOptions.NO_DEADLINE && System.nanoTime() - deadline >= 0L
                ? TerminationReason.DEADLINE_REACHED : null;
    }

    private static boolean reached(long value, long limit) {
        return limit != SolveOptions.UNLIMITED && value >= limit;
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private static void notifySafely(SolveListener listener, RosterSolution solution) {
        try {
            listener.onBestSolution(solution);
        } catch (RuntimeException ignored) {
            // verified best는 유지합니다.
        }
    }

    private SolveResult<RosterSolution> result(
            RosterSolution solution,
            TerminationReason reason,
            long startedNanos,
            long seed,
            Counters counters,
            List<ExhaustivePrefixReassignIntensificationMetrics.BestImprovement> improvements) {
        ExhaustivePrefixReassignIntensificationMetrics metrics =
                new ExhaustivePrefixReassignIntensificationMetrics(
                        counters.generated,
                        counters.evaluated,
                        counters.accepted,
                        counters.rejected,
                        counters.fullVerifications,
                        counters.mismatches,
                        counters.corruptions,
                        candidateLimitPerPass,
                        selector.metrics(),
                        improvements,
                        reason);
        return new SolveResult<>(solution, solution == null ? null : solution.score(), reason,
                counters.accepted, counters.evaluated, elapsedMillis(startedNanos), seed, metrics);
    }

    private static final class Counters {
        private long generated;
        private long evaluated;
        private long accepted;
        private long rejected;
        private long fullVerifications;
        private long mismatches;
        private long corruptions;
    }

    private record Scan(boolean improved, TerminationReason reason) {
        private static final Scan IMPROVEMENT = new Scan(true, null);
        private static final Scan NO_IMPROVEMENT = new Scan(false, null);
    }
}
