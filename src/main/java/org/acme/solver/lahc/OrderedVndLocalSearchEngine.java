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
import org.acme.solver.move.Move;
import org.acme.solver.move.MoveTransaction;
import org.acme.solver.move.ReassignMove;
import org.acme.solver.move.SearchState;
import org.acme.solver.move.StateCorruptionException;
import org.acme.solver.move.SwapMove;
import org.acme.solver.score.FullScoreCalculator;
import org.acme.solver.score.IncrementalScoreCalculator;
import org.acme.solver.score.ScoreMismatchException;

/**
 * Reassign 다음 Swap을 고정 순서로 탐색하는 test-only ordered VND입니다.
 *
 * <p>한 neighborhood에서 엄격한 사전식 개선을 commit하면 Reassign부터 다시 시작하고,
 * Reassign·Swap 모두 개선이 없을 때만 수렴한다. 후보 목록은 stable index를 seed로 회전한
 * 뒤 제한하므로 같은 입력·seed·고정 평가 예산에서는 결정론적이다. 점수 비교는 언제나
 * {@link RosterScore#compareTo(RosterScore)}이며, 하위 좌표 손실 때문에 상위 좌표 승리를
 * 거절하지 않는다.</p>
 */
public final class OrderedVndLocalSearchEngine implements SolverEngine {

    public static final int DEFAULT_CANDIDATE_LIMIT_PER_NEIGHBORHOOD = 256;

    private final FullScoreCalculator fullScoreCalculator;
    private final InitialSolutionBuilder initialSolutionBuilder;
    private final int candidateLimitPerNeighborhood;

    public OrderedVndLocalSearchEngine() {
        this(new FullScoreCalculator(), DEFAULT_CANDIDATE_LIMIT_PER_NEIGHBORHOOD);
    }

    public OrderedVndLocalSearchEngine(int candidateLimitPerNeighborhood) {
        this(new FullScoreCalculator(), candidateLimitPerNeighborhood);
    }

    OrderedVndLocalSearchEngine(FullScoreCalculator fullScoreCalculator, int candidateLimitPerNeighborhood) {
        this.fullScoreCalculator = Objects.requireNonNull(fullScoreCalculator, "fullScoreCalculator");
        this.initialSolutionBuilder = new InitialSolutionBuilder(this.fullScoreCalculator);
        if (candidateLimitPerNeighborhood < 1) {
            throw new IllegalArgumentException("candidateLimitPerNeighborhood은 1 이상이어야 합니다.");
        }
        this.candidateLimitPerNeighborhood = candidateLimitPerNeighborhood;
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
            return result(null, TerminationReason.INITIAL_SOLUTION_FAILED, startedNanos, options.randomSeed(),
                    new Counters(), List.of());
        }
        notifySafely(listener, initial);
        SearchState state = new SearchState(problem, initial);
        IncrementalScoreCalculator incremental = new IncrementalScoreCalculator(problem, initial, fullScoreCalculator);
        RosterSolution best = initial;
        Counters counters = new Counters();
        List<OrderedVndLocalSearchMetrics.BestImprovement> improvements = new ArrayList<>();
        TerminationReason reason = null;
        int neighborhood = 0;

        while (reason == null) {
            reason = requestedTermination(options, deadline);
            if (reason != null) {
                break;
            }
            if (reached(counters.evaluations(), options.maxEvaluations())) {
                reason = TerminationReason.MAX_EVALUATIONS_REACHED;
                break;
            }
            if (reached(counters.accepted(), options.maxIterations())) {
                reason = TerminationReason.MAX_ITERATIONS_REACHED;
                break;
            }
            if (options.maxStagnantEvaluations() != SolveOptions.UNLIMITED
                    && counters.evaluationsSinceImprovement >= options.maxStagnantEvaluations()) {
                reason = TerminationReason.CONVERGED;
                break;
            }

            Neighborhood selected = neighborhood == 0 ? Neighborhood.REASSIGN : Neighborhood.SWAP;
            Evaluation evaluation = scanNeighborhood(problem, state, incremental, selected, options, deadline, counters);
            if (evaluation.reason() != null) {
                reason = evaluation.reason();
                break;
            }
            if (evaluation.improved()) {
                best = state.snapshot();
                improvements.add(new OrderedVndLocalSearchMetrics.BestImprovement(
                        counters.evaluations(), elapsedMillis(startedNanos), best.score(), selected.name()));
                notifySafely(listener, best);
                neighborhood = 0;
            } else if (neighborhood == 0) {
                neighborhood = 1;
            } else {
                reason = TerminationReason.CONVERGED;
            }
        }
        if (reason == null) {
            reason = TerminationReason.COMPLETED;
        }
        RosterScore finalScore = fullScoreCalculator.calculateScore(problem, best);
        counters.fullVerifications++;
        if (!finalScore.equals(best.score())) {
            counters.scoreMismatches++;
            reason = TerminationReason.SCORE_MISMATCH;
        }
        return result(best, reason, startedNanos, options.randomSeed(), counters, improvements);
    }

    private Evaluation scanNeighborhood(
            PlanningProblem problem,
            SearchState state,
            IncrementalScoreCalculator incremental,
            Neighborhood neighborhood,
            SolveOptions options,
            long deadline,
            Counters counters) {
        int[] mutable = problem.mutableShiftIndexes();
        long available = neighborhood == Neighborhood.REASSIGN
                ? (long) mutable.length * Math.max(0, problem.employeeCount() - 1)
                : swapCandidateCount(state, mutable);
        if (neighborhood == Neighborhood.REASSIGN) {
            counters.generatedReassign += available;
        } else {
            counters.generatedSwap += available;
        }
        if (available == 0L) {
            return Evaluation.NO_IMPROVEMENT;
        }
        long offset = Math.floorMod(mix(options.randomSeed(), neighborhood.name(), counters.evaluations()), available);
        long limit = Math.min(available, candidateLimitPerNeighborhood);
        RosterScore baseline = state.score();
        for (long position = 0L; position < limit; position++) {
            TerminationReason termination = requestedTermination(options, deadline);
            if (termination != null) {
                return new Evaluation(false, termination);
            }
            if (reached(counters.evaluations(), options.maxEvaluations())) {
                return new Evaluation(false, TerminationReason.MAX_EVALUATIONS_REACHED);
            }
            Move move = neighborhood == Neighborhood.REASSIGN
                    ? reassignAt(problem, state, mutable, Math.floorMod(offset + position, available))
                    : swapAt(problem, state, mutable, Math.floorMod(offset + position, available));
            try (MoveTransaction transaction = MoveTransaction.open(state, incremental)) {
                transaction.apply(move);
                RosterScore candidate = transaction.verifyCandidate().score();
                counters.fullVerifications++;
                if (neighborhood == Neighborhood.REASSIGN) {
                    counters.evaluatedReassign++;
                } else {
                    counters.evaluatedSwap++;
                }
                if (candidate.compareTo(baseline) <= 0) {
                    transaction.rollback();
                    counters.rejected++;
                    counters.evaluationsSinceImprovement++;
                    continue;
                }
                transaction.commit();
                if (neighborhood == Neighborhood.REASSIGN) {
                    counters.acceptedReassign++;
                } else {
                    counters.acceptedSwap++;
                }
                counters.evaluationsSinceImprovement = 0L;
                return Evaluation.IMPROVEMENT;
            } catch (ScoreMismatchException mismatch) {
                counters.scoreMismatches++;
                return new Evaluation(false, TerminationReason.SCORE_MISMATCH);
            } catch (StateCorruptionException corruption) {
                counters.stateCorruptions++;
                return new Evaluation(false, TerminationReason.STATE_CORRUPTION);
            }
        }
        return Evaluation.NO_IMPROVEMENT;
    }

    private static Move reassignAt(PlanningProblem problem, SearchState state, int[] mutable, long ordinal) {
        int alternatives = problem.employeeCount() - 1;
        int shiftIndex = mutable[(int) (ordinal / alternatives)];
        int oldEmployee = state.employeeIndex(shiftIndex);
        int compressedEmployee = (int) (ordinal % alternatives);
        int newEmployee = compressedEmployee >= oldEmployee ? compressedEmployee + 1 : compressedEmployee;
        return ReassignMove.create(problem, state, shiftIndex, newEmployee);
    }

    private static long swapCandidateCount(SearchState state, int[] mutable) {
        long count = 0L;
        for (int first = 0; first < mutable.length; first++) {
            int firstEmployee = state.employeeIndex(mutable[first]);
            for (int second = first + 1; second < mutable.length; second++) {
                if (firstEmployee != state.employeeIndex(mutable[second])) {
                    count++;
                }
            }
        }
        return count;
    }

    private static Move swapAt(PlanningProblem problem, SearchState state, int[] mutable, long ordinal) {
        long seen = 0L;
        for (int first = 0; first < mutable.length; first++) {
            int firstShift = mutable[first];
            int firstEmployee = state.employeeIndex(firstShift);
            for (int second = first + 1; second < mutable.length; second++) {
                int secondShift = mutable[second];
                if (firstEmployee == state.employeeIndex(secondShift)) {
                    continue;
                }
                if (seen++ == ordinal) {
                    return SwapMove.create(problem, state, firstShift, secondShift);
                }
            }
        }
        throw new IllegalArgumentException("swap ordinal이 candidate 수를 벗어났습니다: " + ordinal);
    }

    private RosterSolution initial(PlanningProblem problem, SolveOptions options) {
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
            return verified(problem, warm);
        }
        InitialSolutionResult built = initialSolutionBuilder.build(problem, options.cancellationToken()::isCancellationRequested);
        return built.succeeded() ? verified(problem, built.solution()) : null;
    }

    private RosterSolution verified(PlanningProblem problem, RosterSolution solution) {
        return new RosterSolution(problem.employeeCount(), solution.employeeIndexByShift(),
                fullScoreCalculator.calculateScore(problem, solution));
    }

    private static void requireTermination(SolveOptions options) {
        if (options.spentLimit().isEmpty() && !options.hasDeadline()
                && options.maxEvaluations() == SolveOptions.UNLIMITED
                && options.maxIterations() == SolveOptions.UNLIMITED) {
            throw new IllegalArgumentException("ordered VND에는 종료 조건이 필요합니다.");
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

    private static long mix(long seed, String neighborhood, long evaluation) {
        long value = seed ^ ((long) neighborhood.hashCode() << 32) ^ evaluation;
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

    private static long elapsedMillis(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private static void notifySafely(SolveListener listener, RosterSolution solution) {
        try {
            listener.onBestSolution(solution);
        } catch (RuntimeException ignored) {
            // listener 실패는 verified best나 transaction 상태에 영향을 주지 않습니다.
        }
    }

    private SolveResult<RosterSolution> result(
            RosterSolution solution, TerminationReason reason, long startedNanos, long seed,
            Counters counters, List<OrderedVndLocalSearchMetrics.BestImprovement> improvements) {
        OrderedVndLocalSearchMetrics metrics = new OrderedVndLocalSearchMetrics(
                counters.generatedReassign, counters.generatedSwap,
                counters.evaluatedReassign, counters.evaluatedSwap,
                counters.acceptedReassign, counters.acceptedSwap, counters.rejected,
                counters.fullVerifications, counters.scoreMismatches, counters.stateCorruptions,
                candidateLimitPerNeighborhood, improvements, reason);
        return new SolveResult<>(solution, solution == null ? null : solution.score(), reason,
                counters.accepted(), counters.evaluations(), elapsedMillis(startedNanos), seed, metrics);
    }

    private enum Neighborhood {
        REASSIGN,
        SWAP
    }

    private record Evaluation(boolean improved, TerminationReason reason) {
        private static final Evaluation IMPROVEMENT = new Evaluation(true, null);
        private static final Evaluation NO_IMPROVEMENT = new Evaluation(false, null);
    }

    private static final class Counters {
        private long generatedReassign;
        private long generatedSwap;
        private long evaluatedReassign;
        private long evaluatedSwap;
        private long acceptedReassign;
        private long acceptedSwap;
        private long rejected;
        private long fullVerifications;
        private long scoreMismatches;
        private long stateCorruptions;
        private long evaluationsSinceImprovement;

        private long evaluations() {
            return evaluatedReassign + evaluatedSwap;
        }

        private long accepted() {
            return acceptedReassign + acceptedSwap;
        }
    }
}
