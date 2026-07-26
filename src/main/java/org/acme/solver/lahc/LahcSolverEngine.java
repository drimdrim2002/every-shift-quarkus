package org.acme.solver.lahc;

import java.time.Duration;
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
import org.acme.solver.initial.InitialSolutionFailureCode;
import org.acme.solver.initial.InitialSolutionResult;
import org.acme.solver.move.Move;
import org.acme.solver.move.MoveTransaction;
import org.acme.solver.move.SearchState;
import org.acme.solver.move.StateCorruptionException;
import org.acme.solver.score.FullScoreCalculator;
import org.acme.solver.score.IncrementalScoreCalculator;
import org.acme.solver.score.ScoreMismatchException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

/** complete solution을 LAHC로 개선하는 POJO 엔진입니다. */
@ApplicationScoped
@Typed(LahcSolverEngine.class)
public class LahcSolverEngine implements SolverEngine {

    private static final Logger LOG = LoggerFactory.getLogger(LahcSolverEngine.class);

    @ConfigProperty(name = "solver.lahc.history-length", defaultValue = "100")
    int configuredHistoryLength = 100;

    @ConfigProperty(name = "solver.lahc.full-verification-interval", defaultValue = "1000")
    int fullVerificationInterval = 1000;

    private final InitialSolutionBuilder initialSolutionBuilder;
    private final FullScoreCalculator fullScoreCalculator;
    private final LahcMoveSelector moveSelector;
    private final Integer fixedHistoryLength;

    public LahcSolverEngine() {
        this.fullScoreCalculator = new FullScoreCalculator();
        this.initialSolutionBuilder = new InitialSolutionBuilder(fullScoreCalculator);
        this.moveSelector = new SeededMoveSelector();
        this.fixedHistoryLength = null;
    }

    public LahcSolverEngine(
            InitialSolutionBuilder initialSolutionBuilder,
            FullScoreCalculator fullScoreCalculator,
            LahcMoveSelector moveSelector,
            int historyLength) {
        if (historyLength <= 0) {
            throw new IllegalArgumentException("historyLength는 양수여야 합니다.");
        }
        this.initialSolutionBuilder = Objects.requireNonNull(initialSolutionBuilder, "initialSolutionBuilder");
        this.fullScoreCalculator = Objects.requireNonNull(fullScoreCalculator, "fullScoreCalculator");
        this.moveSelector = Objects.requireNonNull(moveSelector, "moveSelector");
        this.fixedHistoryLength = historyLength;
    }

    @Override
    public SolveResult<RosterSolution> solve(
            PlanningProblem problem,
            SolveOptions options,
            SolveListener listener) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(listener, "listener");
        validateTermination(options);

        long startedNanos = System.nanoTime();
        long deadlineNanos = effectiveDeadline(startedNanos, options);
        TerminationReason beforeStart = requestedTermination(options, deadlineNanos);
        if (beforeStart != null) {
            return result(null, beforeStart, 0L, 0L, startedNanos, options.randomSeed());
        }

        RosterSolution initial = verifiedWarmStart(problem, options).orElse(null);
        if (initial == null) {
            InitialSolutionResult initialResult = initialSolutionBuilder.build(
                    problem, () -> requestedTermination(options, deadlineNanos) != null);
            if (!initialResult.succeeded()) {
                TerminationReason interruptedReason = requestedTermination(options, deadlineNanos);
                TerminationReason reason = initialResult.failures().getFirst().code()
                                == InitialSolutionFailureCode.INTERRUPTED
                        ? Objects.requireNonNullElse(interruptedReason, TerminationReason.CANCELLED)
                        : TerminationReason.INITIAL_SOLUTION_FAILED;
                return result(null, reason, 0L, 0L, startedNanos, options.randomSeed());
            }
            initial = initialResult.solution();
        }

        notifyListenerSafely(listener, initial);
        SearchState state = new SearchState(problem, initial);
        IncrementalScoreCalculator incremental = new IncrementalScoreCalculator(
                problem, initial, fullScoreCalculator);
        LahcAcceptancePolicy acceptance = new LahcAcceptancePolicy(
                fixedHistoryLength == null ? configuredHistoryLength : fixedHistoryLength,
                initial.score());
        SplittableRandom random = new SplittableRandom(options.randomSeed());

        RosterSolution best = initial;
        long iterations = 0L;
        long evaluations = 0L;
        long lastBestImprovementEvaluation = 0L;
        TerminationReason reason = null;

        while (reason == null) {
            reason = requestedTermination(options, deadlineNanos);
            if (reason != null) {
                break;
            }
            if (limitReached(evaluations, options.maxEvaluations())) {
                reason = TerminationReason.MAX_EVALUATIONS_REACHED;
                break;
            }
            if (limitReached(iterations, options.maxIterations())) {
                reason = TerminationReason.MAX_ITERATIONS_REACHED;
                break;
            }
            if (options.maxStagnantEvaluations() != SolveOptions.UNLIMITED
                    && evaluations - lastBestImprovementEvaluation >= options.maxStagnantEvaluations()) {
                reason = TerminationReason.CONVERGED;
                break;
            }

            Optional<Move> selected = moveSelector.select(problem, state, random);
            if (selected.isEmpty()) {
                reason = TerminationReason.CONVERGED;
                break;
            }

            RosterScore currentScore = state.score();
            try (MoveTransaction transaction = MoveTransaction.open(state, incremental)) {
                transaction.apply(selected.get());
                TerminationReason duringTransaction = requestedTermination(options, deadlineNanos);
                if (duringTransaction != null) {
                    transaction.rollback();
                    reason = duringTransaction;
                    break;
                }

                RosterScore candidateScore = transaction.candidateScore();
                boolean requiresFullVerification = candidateScore.compareTo(best.score()) > 0
                        || (evaluations + 1L) % validatedVerificationInterval() == 0L;
                if (requiresFullVerification) {
                    candidateScore = transaction.verifyCandidate().score();
                }
                duringTransaction = requestedTermination(options, deadlineNanos);
                if (duringTransaction != null) {
                    transaction.rollback();
                    reason = duringTransaction;
                    break;
                }

                LahcAcceptancePolicy.Decision decision = acceptance.consider(
                        currentScore, candidateScore);
                evaluations++;
                iterations++;
                if (decision.accepted()) {
                    transaction.commit();
                    RosterSolution current = state.snapshot();
                    if (current.score().compareTo(best.score()) > 0) {
                        best = current;
                        lastBestImprovementEvaluation = evaluations;
                        notifyListenerSafely(listener, best);
                    }
                } else {
                    transaction.rollback();
                }
            } catch (ScoreMismatchException mismatch) {
                LOG.error("LAHC full/incremental score mismatch", mismatch);
                best = betterVerified(best, mismatch.lastVerifiedBest());
                reason = TerminationReason.SCORE_MISMATCH;
            } catch (StateCorruptionException corruption) {
                LOG.error("LAHC state corruption", corruption);
                best = betterVerified(best, corruption.lastVerifiedBest());
                reason = TerminationReason.STATE_CORRUPTION;
            }
        }

        if (reason == null) {
            reason = TerminationReason.COMPLETED;
        }
        if (!best.score().isFeasible() && isNormalCompletion(reason)) {
            reason = TerminationReason.NO_FEASIBLE_SOLUTION;
        }
        return result(best, reason, iterations, evaluations, startedNanos, options.randomSeed());
    }

    private Optional<RosterSolution> verifiedWarmStart(
            PlanningProblem problem, SolveOptions options) {
        if (options.warmStart().isEmpty()) {
            return Optional.empty();
        }
        RosterSolution warmStart = options.warmStart().orElseThrow();
        if (warmStart.employeeCount() != problem.employeeCount()
                || warmStart.shiftCount() != problem.shiftCount()) {
            return Optional.empty();
        }
        for (int shiftIndex : problem.pinnedShiftIndexes()) {
            if (warmStart.employeeIndex(shiftIndex) != problem.initialEmployeeIndex(shiftIndex)) {
                return Optional.empty();
            }
        }
        RosterScore verified = fullScoreCalculator.calculateScore(problem, warmStart);
        return Optional.of(new RosterSolution(
                problem.employeeCount(), warmStart.employeeIndexByShift(), verified));
    }

    private static void validateTermination(SolveOptions options) {
        if (options.spentLimit().isEmpty()
                && !options.hasDeadline()
                && options.maxEvaluations() == SolveOptions.UNLIMITED
                && options.maxIterations() == SolveOptions.UNLIMITED
                && options.maxStagnantEvaluations() == SolveOptions.UNLIMITED) {
            throw new IllegalArgumentException("LAHC에는 하나 이상의 종료 조건이 필요합니다.");
        }
    }

    private int validatedVerificationInterval() {
        if (fullVerificationInterval <= 0) {
            throw new IllegalArgumentException("full verification interval은 양수여야 합니다.");
        }
        return fullVerificationInterval;
    }

    private static long effectiveDeadline(long startedNanos, SolveOptions options) {
        long result = options.deadlineNanos();
        if (options.spentLimit().isPresent()) {
            long spentDeadline = saturatedAdd(startedNanos, options.spentLimit().orElseThrow().toNanos());
            if (result == SolveOptions.NO_DEADLINE || isEarlier(spentDeadline, result, startedNanos)) {
                result = spentDeadline;
            }
        }
        return result;
    }

    private static boolean isEarlier(long candidate, long current, long origin) {
        return candidate - origin < current - origin;
    }

    private static TerminationReason requestedTermination(
            SolveOptions options, long deadlineNanos) {
        if (options.cancellationToken().isCancellationRequested()) {
            return TerminationReason.CANCELLED;
        }
        if (deadlineNanos != SolveOptions.NO_DEADLINE
                && System.nanoTime() - deadlineNanos >= 0L) {
            return TerminationReason.DEADLINE_REACHED;
        }
        return null;
    }

    private static boolean limitReached(long count, long limit) {
        return limit != SolveOptions.UNLIMITED && count >= limit;
    }

    private static boolean isNormalCompletion(TerminationReason reason) {
        return reason == TerminationReason.COMPLETED
                || reason == TerminationReason.CONVERGED
                || reason == TerminationReason.MAX_EVALUATIONS_REACHED
                || reason == TerminationReason.MAX_ITERATIONS_REACHED;
    }

    private static RosterSolution betterVerified(
            RosterSolution first, RosterSolution second) {
        if (second == null) {
            return first;
        }
        return first == null || second.score().compareTo(first.score()) > 0 ? second : first;
    }

    private static void notifyListenerSafely(SolveListener listener, RosterSolution solution) {
        try {
            listener.onBestSolution(solution);
        } catch (RuntimeException listenerFailure) {
            LOG.warn("SolveListener failed but LAHC search continues", listenerFailure);
        }
    }

    private static SolveResult<RosterSolution> result(
            RosterSolution solution,
            TerminationReason reason,
            long iterations,
            long evaluations,
            long startedNanos,
            long seed) {
        return new SolveResult<>(
                solution,
                solution == null ? null : solution.score(),
                reason,
                iterations,
                evaluations,
                Duration.ofNanos(System.nanoTime() - startedNanos).toMillis(),
                seed);
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
