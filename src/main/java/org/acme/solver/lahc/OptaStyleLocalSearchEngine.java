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
import org.acme.solver.initial.InitialSolutionFailureCode;
import org.acme.solver.initial.InitialSolutionResult;
import org.acme.solver.move.Move;
import org.acme.solver.move.MoveTransaction;
import org.acme.solver.move.SearchState;
import org.acme.solver.move.StateCorruptionException;
import org.acme.solver.score.FullScoreCalculator;
import org.acme.solver.score.IncrementalScoreCalculator;
import org.acme.solver.score.ScoreMismatchException;

/**
 * Phase 6 알고리즘 비교를 위한 Change/Swap local search runner입니다.
 *
 * <p>CDI bean이나 production engine selector에는 등록하지 않는다. 이 runner는 baseline ALNS와
 * 별도 benchmark profile에서만 사용하며, score 의미는 기존 {@link FullScoreCalculator}와
 * {@link IncrementalScoreCalculator}를 그대로 사용한다.</p>
 */
public final class OptaStyleLocalSearchEngine implements SolverEngine {

    public static final int DEFAULT_HISTORY_LENGTH = 400;
    public static final int DEFAULT_FULL_VERIFICATION_INTERVAL = 1_000;
    /** ALNS baseline과 같은 pre-search feasible warm-start 복구 예산입니다. */
    public static final long DEFAULT_INITIAL_FEASIBILITY_EVALUATIONS = 10_000L;

    private final InitialSolutionBuilder initialSolutionBuilder;
    private final FullScoreCalculator fullScoreCalculator;
    private final LahcMoveSelector moveSelector;
    private final int historyLength;
    private final int fullVerificationInterval;

    public OptaStyleLocalSearchEngine() {
        this(new FullScoreCalculator(), new OptaStyleMoveSelector(), DEFAULT_HISTORY_LENGTH,
                DEFAULT_FULL_VERIFICATION_INTERVAL);
    }

    public OptaStyleLocalSearchEngine(
            FullScoreCalculator fullScoreCalculator,
            LahcMoveSelector moveSelector,
            int historyLength) {
        this(fullScoreCalculator, moveSelector, historyLength, DEFAULT_FULL_VERIFICATION_INTERVAL);
    }

    public OptaStyleLocalSearchEngine(
            FullScoreCalculator fullScoreCalculator,
            LahcMoveSelector moveSelector,
            int historyLength,
            int fullVerificationInterval) {
        this.fullScoreCalculator = Objects.requireNonNull(fullScoreCalculator, "fullScoreCalculator");
        this.initialSolutionBuilder = new InitialSolutionBuilder(this.fullScoreCalculator);
        this.moveSelector = Objects.requireNonNull(moveSelector, "moveSelector");
        if (historyLength <= 0) {
            throw new IllegalArgumentException("historyLength는 양수여야 합니다.");
        }
        if (fullVerificationInterval <= 0) {
            throw new IllegalArgumentException("fullVerificationInterval은 양수여야 합니다.");
        }
        this.historyLength = historyLength;
        this.fullVerificationInterval = fullVerificationInterval;
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
            return result(null, beforeStart, 0L, 0L, startedNanos, options.randomSeed(),
                    0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, List.of(), false);
        }

        RosterSolution initial = verifiedWarmStart(problem, options).orElse(null);
        if (initial == null) {
            InitialSolutionResult initialResult = initialSolutionBuilder.build(
                    problem, () -> requestedTermination(options, deadlineNanos) != null);
            if (!initialResult.succeeded()) {
                TerminationReason interrupted = requestedTermination(options, deadlineNanos);
                TerminationReason reason = initialResult.failures().getFirst().code()
                                == InitialSolutionFailureCode.INTERRUPTED
                        ? Objects.requireNonNullElse(interrupted, TerminationReason.CANCELLED)
                        : TerminationReason.INITIAL_SOLUTION_FAILED;
                return result(null, reason, 0L, 0L, startedNanos, options.randomSeed(),
                        0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, List.of(), false);
            }
            initial = initialResult.solution();
        }

        long initialFeasibilityEvaluations = 0L;
        if (!initial.score().isFeasible()) {
            SolveResult<RosterSolution> bootstrap = new LahcSolverEngine(
                    initialSolutionBuilder,
                    fullScoreCalculator,
                    new SeededMoveSelector(),
                    100).solve(
                            problem,
                            SolveOptions.builder()
                                    .deadlineNanos(deadlineNanos)
                                    .maxEvaluations(DEFAULT_INITIAL_FEASIBILITY_EVALUATIONS)
                                    .randomSeed(deriveInitialFeasibilitySeed(options.randomSeed()))
                                    .warmStart(initial)
                                    .cancellationToken(options.cancellationToken())
                                    .build(),
                            SolveListener.noop());
            initialFeasibilityEvaluations = bootstrap.evaluationCount();
            if (bootstrap.terminationReason() == TerminationReason.SCORE_MISMATCH
                    || bootstrap.terminationReason() == TerminationReason.STATE_CORRUPTION) {
                return result(
                        bootstrap.bestSolution(),
                        bootstrap.terminationReason(),
                        0L,
                        0L,
                        startedNanos,
                        options.randomSeed(),
                        initialFeasibilityEvaluations,
                        0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, List.of(), false);
            }
            if (bootstrap.bestSolution() != null) {
                RosterSolution bootstrapBest = bootstrap.bestSolution();
                RosterScore verifiedScore = fullScoreCalculator.calculateScore(problem, bootstrapBest);
                if (!verifiedScore.equals(bootstrapBest.score())) {
                    return result(
                            bootstrapBest,
                            TerminationReason.SCORE_MISMATCH,
                            0L,
                            0L,
                            startedNanos,
                            options.randomSeed(),
                            initialFeasibilityEvaluations,
                            0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 1L, 0L, 1L, List.of(), true);
                }
                initial = new RosterSolution(
                        problem.employeeCount(), bootstrapBest.employeeIndexByShift(), verifiedScore);
            }
            if (!initial.score().isFeasible()) {
                TerminationReason interrupted = requestedTermination(options, deadlineNanos);
                return result(
                        initial,
                        Objects.requireNonNullElse(interrupted, TerminationReason.NO_FEASIBLE_SOLUTION),
                        0L,
                        0L,
                        startedNanos,
                        options.randomSeed(),
                        initialFeasibilityEvaluations,
                        0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, List.of(), false);
            }
        }

        notifyListenerSafely(listener, initial);
        SearchState state = new SearchState(problem, initial);
        IncrementalScoreCalculator incremental = new IncrementalScoreCalculator(
                problem, initial, fullScoreCalculator);
        OptaStyleLateAcceptancePolicy acceptance = new OptaStyleLateAcceptancePolicy(
                historyLength, initial.score());
        SplittableRandom random = new SplittableRandom(options.randomSeed());

        RosterSolution best = initial;
        long iterations = 0L;
        long evaluations = 0L;
        long rejectedCandidates = 0L;
        long cancelledCandidates = 0L;
        long changeCandidates = 0L;
        long swapCandidates = 0L;
        long acceptedChangeSteps = 0L;
        long acceptedSwapSteps = 0L;
        long rejectedChangeCandidates = 0L;
        long rejectedSwapCandidates = 0L;
        long scoreMismatchFailures = 0L;
        long stateCorruptionFailures = 0L;
        long fullVerificationCount = 0L;
        long lastBestImprovementEvaluation = 0L;
        List<OptaStyleLocalSearchMetrics.BestImprovement> bestImprovements = new ArrayList<>();
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

            boolean acceptedStep = false;
            while (!acceptedStep && reason == null) {
                reason = requestedTermination(options, deadlineNanos);
                if (reason != null) {
                    break;
                }
                if (limitReached(evaluations, options.maxEvaluations())) {
                    reason = TerminationReason.MAX_EVALUATIONS_REACHED;
                    break;
                }

                Optional<Move> selected = moveSelector.select(problem, state, random);
                if (selected.isEmpty()) {
                    reason = TerminationReason.CONVERGED;
                    break;
                }
                Move move = selected.orElseThrow();
                if (!"REASSIGN".equals(move.moveType()) && !"SWAP".equals(move.moveType())) {
                    throw new IllegalStateException("Opta-style core selector는 Change/Swap만 반환해야 합니다: "
                            + move.moveType());
                }

                RosterScore currentScore = state.score();
                try (MoveTransaction transaction = MoveTransaction.open(state, incremental)) {
                    transaction.apply(move);
                    TerminationReason duringTransaction = requestedTermination(options, deadlineNanos);
                    if (duringTransaction != null) {
                        transaction.rollback();
                        reason = duringTransaction;
                        break;
                    }

                    RosterScore candidateScore = transaction.candidateScore();
                    boolean fullVerified = (evaluations + 1L) % fullVerificationInterval == 0L
                            || candidateScore.compareTo(best.score()) > 0;
                    if (fullVerified) {
                        candidateScore = transaction.verifyCandidate().score();
                        fullVerificationCount++;
                    }
                    evaluations++;
                    if ("REASSIGN".equals(move.moveType())) {
                        changeCandidates++;
                    } else {
                        swapCandidates++;
                    }

                    duringTransaction = requestedTermination(options, deadlineNanos);
                    if (duringTransaction != null) {
                        transaction.rollback();
                        cancelledCandidates++;
                        reason = duringTransaction;
                        break;
                    }

                    OptaStyleLateAcceptancePolicy.Decision decision = acceptance.evaluate(
                            currentScore, candidateScore);
                    if (!decision.accepted()) {
                        transaction.rollback();
                        rejectedCandidates++;
                        if ("REASSIGN".equals(move.moveType())) {
                            rejectedChangeCandidates++;
                        } else {
                            rejectedSwapCandidates++;
                        }
                        continue;
                    }

                    if (!fullVerified) {
                        candidateScore = transaction.verifyCandidate().score();
                        fullVerificationCount++;
                        decision = acceptance.evaluate(currentScore, candidateScore);
                        if (!decision.accepted()) {
                            transaction.rollback();
                            rejectedCandidates++;
                            if ("REASSIGN".equals(move.moveType())) {
                                rejectedChangeCandidates++;
                            } else {
                                rejectedSwapCandidates++;
                            }
                            continue;
                        }
                    }

                    transaction.commit();
                    acceptance.completeAcceptedStep(decision, state.score());
                    iterations++;
                    if ("REASSIGN".equals(move.moveType())) {
                        acceptedChangeSteps++;
                    } else {
                        acceptedSwapSteps++;
                    }
                    acceptedStep = true;
                    RosterSolution current = state.snapshot();
                    if (current.score().compareTo(best.score()) > 0) {
                        best = current;
                        lastBestImprovementEvaluation = evaluations;
                        bestImprovements.add(new OptaStyleLocalSearchMetrics.BestImprovement(
                                evaluations, current.score(), move.moveType()));
                        notifyListenerSafely(listener, best);
                    }
                } catch (ScoreMismatchException mismatch) {
                    scoreMismatchFailures++;
                    best = betterVerified(best, mismatch.lastVerifiedBest());
                    reason = TerminationReason.SCORE_MISMATCH;
                } catch (StateCorruptionException corruption) {
                    stateCorruptionFailures++;
                    best = betterVerified(best, corruption.lastVerifiedBest());
                    reason = TerminationReason.STATE_CORRUPTION;
                }
            }
        }

        if (reason == null) {
            reason = TerminationReason.COMPLETED;
        }

        boolean finalValidationPerformed = false;
        if (best != null) {
            finalValidationPerformed = true;
            RosterScore fullScore = fullScoreCalculator.calculateScore(problem, best);
            fullVerificationCount++;
            if (!fullScore.equals(best.score())) {
                scoreMismatchFailures++;
                reason = TerminationReason.SCORE_MISMATCH;
            }
        }
        if (best != null && !best.score().isFeasible() && isNormalCompletion(reason)) {
            reason = TerminationReason.NO_FEASIBLE_SOLUTION;
        }

        return result(best, reason, iterations, evaluations, startedNanos, options.randomSeed(),
                initialFeasibilityEvaluations,
                rejectedCandidates, cancelledCandidates, changeCandidates, swapCandidates,
                acceptedChangeSteps, acceptedSwapSteps, rejectedChangeCandidates, rejectedSwapCandidates,
                scoreMismatchFailures,
                stateCorruptionFailures, fullVerificationCount, bestImprovements, finalValidationPerformed);
    }

    private Optional<RosterSolution> verifiedWarmStart(PlanningProblem problem, SolveOptions options) {
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
            throw new IllegalArgumentException("Opta-style local search에는 하나 이상의 종료 조건이 필요합니다.");
        }
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

    private static TerminationReason requestedTermination(SolveOptions options, long deadlineNanos) {
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

    private static RosterSolution betterVerified(RosterSolution first, RosterSolution second) {
        if (second == null) {
            return first;
        }
        return first == null || second.score().compareTo(first.score()) > 0 ? second : first;
    }

    private static void notifyListenerSafely(SolveListener listener, RosterSolution solution) {
        try {
            listener.onBestSolution(solution);
        } catch (RuntimeException ignored) {
            // Listener 실패는 verified best를 바꾸지 않습니다.
        }
    }

    private SolveResult<RosterSolution> result(
            RosterSolution solution,
            TerminationReason reason,
            long iterations,
            long evaluations,
            long startedNanos,
            long seed,
            long initialFeasibilityEvaluations,
            long rejectedCandidates,
            long cancelledCandidates,
            long changeCandidates,
            long swapCandidates,
            long acceptedChangeSteps,
            long acceptedSwapSteps,
            long rejectedChangeCandidates,
            long rejectedSwapCandidates,
            long scoreMismatchFailures,
            long stateCorruptionFailures,
            long fullVerificationCount,
            List<OptaStyleLocalSearchMetrics.BestImprovement> bestImprovements,
            boolean finalValidationPerformed) {
        OptaStyleLocalSearchMetrics metrics = new OptaStyleLocalSearchMetrics(
                historyLength,
                fullVerificationInterval,
                evaluations,
                iterations,
                rejectedCandidates,
                cancelledCandidates,
                changeCandidates,
                swapCandidates,
                acceptedChangeSteps,
                acceptedSwapSteps,
                rejectedChangeCandidates,
                rejectedSwapCandidates,
                scoreMismatchFailures,
                stateCorruptionFailures,
                fullVerificationCount,
                initialFeasibilityEvaluations,
                bestImprovements,
                finalValidationPerformed,
                reason);
        return new SolveResult<>(
                solution,
                solution == null ? null : solution.score(),
                reason,
                iterations,
                evaluations,
                Duration.ofNanos(System.nanoTime() - startedNanos).toMillis(),
                seed,
                metrics);
    }

    private SolveResult<RosterSolution> result(
            RosterSolution solution,
            TerminationReason reason,
            long iterations,
            long evaluations,
            long startedNanos,
            long seed,
            long rejectedCandidates,
            long cancelledCandidates,
            long changeCandidates,
            long swapCandidates,
            long acceptedChangeSteps,
            long acceptedSwapSteps,
            long rejectedChangeCandidates,
            long rejectedSwapCandidates,
            long scoreMismatchFailures,
            long stateCorruptionFailures,
            long fullVerificationCount,
            List<OptaStyleLocalSearchMetrics.BestImprovement> bestImprovements,
            boolean finalValidationPerformed) {
        return result(
                solution,
                reason,
                iterations,
                evaluations,
                startedNanos,
                seed,
                0L,
                rejectedCandidates,
                cancelledCandidates,
                changeCandidates,
                swapCandidates,
                acceptedChangeSteps,
                acceptedSwapSteps,
                rejectedChangeCandidates,
                rejectedSwapCandidates,
                scoreMismatchFailures,
                stateCorruptionFailures,
                fullVerificationCount,
                bestImprovements,
                finalValidationPerformed);
    }

    private static long deriveInitialFeasibilitySeed(long searchSeed) {
        long value = searchSeed ^ 0x494E49545F464541L;
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
}
