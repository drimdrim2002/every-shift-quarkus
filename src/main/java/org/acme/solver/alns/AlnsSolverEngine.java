package org.acme.solver.alns;

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
import org.acme.solver.lahc.LahcSolverEngine;
import org.acme.solver.lahc.SeededMoveSelector;
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

/** 사전식 SA와 독립 adaptive operator selection을 사용하는 production POJO_ALNS 엔진입니다. */
@ApplicationScoped
@Typed(AlnsSolverEngine.class)
public class AlnsSolverEngine implements SolverEngine {

    private static final Logger LOG = LoggerFactory.getLogger(AlnsSolverEngine.class);

    @ConfigProperty(name = "solver.alns.destroy-rate", defaultValue = "0.05")
    double destroyRate = 0.05d;

    @ConfigProperty(name = "solver.alns.q-min", defaultValue = "1")
    int qMin = 1;

    @ConfigProperty(name = "solver.alns.q-max", defaultValue = "8")
    int qMax = 8;

    @ConfigProperty(name = "solver.alns.absolute-removal-limit", defaultValue = "12")
    int absoluteRemovalLimit = 12;

    @ConfigProperty(name = "solver.alns.max-repair-attempts", defaultValue = "2")
    int maxRepairAttempts = 2;

    @ConfigProperty(name = "solver.alns.sa.target-initial-acceptance", defaultValue = "0.2")
    double targetInitialAcceptance = 0.2d;

    @ConfigProperty(name = "solver.alns.sa.final-temperature-ratio", defaultValue = "0.01")
    double finalTemperatureRatio = 0.01d;

    @ConfigProperty(name = "solver.alns.sa.fallback-cooling-evaluations", defaultValue = "10000")
    long fallbackCoolingEvaluations = 10_000L;

    @ConfigProperty(name = "solver.alns.calibration-attempts", defaultValue = "64")
    int calibrationAttempts = 64;

    @ConfigProperty(name = "solver.alns.initial-feasibility-evaluations", defaultValue = "10000")
    long initialFeasibilityEvaluations = 10_000L;

    @ConfigProperty(name = "solver.alns.final-validation-reserve-ms", defaultValue = "10")
    long finalValidationReserveMillis = 10L;

    @ConfigProperty(name = "solver.alns.adaptive.initial-weight", defaultValue = "1.0")
    double initialWeight = 1.0d;

    @ConfigProperty(name = "solver.alns.adaptive.minimum-weight", defaultValue = "0.1")
    double minimumWeight = 0.1d;

    @ConfigProperty(name = "solver.alns.adaptive.reaction-factor", defaultValue = "0.2")
    double reactionFactor = 0.2d;

    @ConfigProperty(name = "solver.alns.adaptive.segment-length", defaultValue = "100")
    int segmentLength = 100;

    @ConfigProperty(name = "solver.alns.adaptive.reward-global-best", defaultValue = "10")
    double globalBestReward = 10.0d;

    @ConfigProperty(name = "solver.alns.adaptive.reward-current-improvement", defaultValue = "5")
    double currentImprovementReward = 5.0d;

    @ConfigProperty(name = "solver.alns.adaptive.reward-accepted-worsening", defaultValue = "1")
    double acceptedWorseningReward = 1.0d;

    @ConfigProperty(name = "solver.alns.adaptive.reward-rejected", defaultValue = "0")
    double rejectedReward = 0.0d;

    private final InitialSolutionBuilder initialSolutionBuilder;
    private final FullScoreCalculator fullScoreCalculator;
    private final List<DestroyOperator> destroyOperators;
    private final List<RepairOperator> repairOperators;
    private final OperatorCompatibilityMatrix compatibilityMatrix;
    private final AlnsSolverConfig fixedConfig;

    public AlnsSolverEngine() {
        this.fullScoreCalculator = new FullScoreCalculator();
        this.initialSolutionBuilder = new InitialSolutionBuilder(fullScoreCalculator);
        this.destroyOperators = List.of(
                new RandomRemoval(),
                new RelatedShiftRemoval(),
                new PreceptorRelationGroupRemoval());
        this.repairOperators = List.of(
                new GreedyRepair(),
                new Regret2Repair(),
                new RelationAwareRepair());
        this.compatibilityMatrix = OperatorCompatibilityMatrix.baseline();
        this.fixedConfig = null;
    }

    public AlnsSolverEngine(
            InitialSolutionBuilder initialSolutionBuilder,
            FullScoreCalculator fullScoreCalculator,
            List<? extends DestroyOperator> destroyOperators,
            List<? extends RepairOperator> repairOperators,
            OperatorCompatibilityMatrix compatibilityMatrix,
            AlnsSolverConfig config) {
        this.initialSolutionBuilder = Objects.requireNonNull(initialSolutionBuilder, "initialSolutionBuilder");
        this.fullScoreCalculator = Objects.requireNonNull(fullScoreCalculator, "fullScoreCalculator");
        this.destroyOperators = List.copyOf(Objects.requireNonNull(destroyOperators, "destroyOperators"));
        this.repairOperators = List.copyOf(Objects.requireNonNull(repairOperators, "repairOperators"));
        this.compatibilityMatrix = Objects.requireNonNull(compatibilityMatrix, "compatibilityMatrix");
        this.fixedConfig = Objects.requireNonNull(config, "config");
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
        AlnsSolverConfig config = runtimeConfig();

        long startedNanos = System.nanoTime();
        long totalDeadlineNanos = effectiveDeadline(startedNanos, options);
        long searchDeadlineNanos = reserveFinalValidationTime(
                startedNanos, totalDeadlineNanos, config.finalValidationReserve());
        String profile = profile(options);
        TerminationReason beforeStart = requestedTermination(options, totalDeadlineNanos);
        if (beforeStart != null) {
            return emptyResult(
                    beforeStart, 0L, 0L, startedNanos, options.randomSeed(), profile, config);
        }

        RosterSolution initial = verifiedWarmStart(problem, options).orElse(null);
        if (initial == null) {
            InitialSolutionResult initialResult = initialSolutionBuilder.build(
                    problem, () -> requestedTermination(options, searchDeadlineNanos) != null);
            if (!initialResult.succeeded()) {
                TerminationReason interrupted = requestedTermination(options, searchDeadlineNanos);
                TerminationReason reason = initialResult.failures().getFirst().code()
                                == InitialSolutionFailureCode.INTERRUPTED
                        ? Objects.requireNonNullElse(interrupted, TerminationReason.CANCELLED)
                        : TerminationReason.INITIAL_SOLUTION_FAILED;
                return emptyResult(
                        reason, 0L, 0L, startedNanos, options.randomSeed(), profile, config);
            }
            initial = initialResult.solution();
        }

        long initialFeasibilitySeed = deriveInitialFeasibilitySeed(options.randomSeed());
        long initialFeasibilityEvaluationCount = 0L;
        if (!initial.score().isFeasible()
                && config.initialFeasibilityEvaluationBudget() > 0L) {
            SolveOptions bootstrapOptions = SolveOptions.builder()
                    .deadlineNanos(searchDeadlineNanos)
                    .maxEvaluations(config.initialFeasibilityEvaluationBudget())
                    .randomSeed(initialFeasibilitySeed)
                    .warmStart(initial)
                    .cancellationToken(options.cancellationToken())
                    .build();
            SolveResult<RosterSolution> bootstrap = new LahcSolverEngine(
                    initialSolutionBuilder,
                    fullScoreCalculator,
                    new SeededMoveSelector(),
                    100).solve(problem, bootstrapOptions, SolveListener.noop());
            initialFeasibilityEvaluationCount = bootstrap.evaluationCount();
            if (bootstrap.bestSolution() != null) {
                initial = bootstrap.bestSolution();
            }
            if (bootstrap.terminationReason() == TerminationReason.SCORE_MISMATCH
                    || bootstrap.terminationReason() == TerminationReason.STATE_CORRUPTION) {
                return emptyResult(
                        bootstrap.terminationReason(),
                        0L,
                        0L,
                        startedNanos,
                        options.randomSeed(),
                        profile,
                        config,
                        initialFeasibilitySeed,
                        initialFeasibilityEvaluationCount);
            }
        }

        RosterSolution bestOverall = initial;
        RosterSolution verifiedFeasibleBest = initial.score().isFeasible() ? initial : null;
        if (verifiedFeasibleBest != null) {
            notifyListenerSafely(listener, verifiedFeasibleBest);
        }

        SaAcceptanceConfig saConfig = config.saConfig();
        SaCalibrationResult calibration = new SaCalibrator(fullScoreCalculator).calibrate(
                problem,
                initial,
                destroyOperators,
                repairOperators,
                compatibilityMatrix,
                config.iterationConfig(),
                config.calibrationAttemptBudget(),
                options.randomSeed(),
                saConfig.targetInitialAcceptanceProbability(),
                () -> requestedTermination(options, searchDeadlineNanos) != null);

        SplittableRandom searchRandom = new SplittableRandom(options.randomSeed());
        AdaptiveOperatorSelector selector = new AdaptiveOperatorSelector(
                destroyOperators,
                repairOperators,
                compatibilityMatrix,
                config.adaptiveConfig(),
                searchRandom);
        long coolingEvaluations = options.maxEvaluations() == SolveOptions.UNLIMITED
                ? saConfig.fallbackCoolingEvaluations()
                : options.maxEvaluations();
        LexicographicSaAcceptance acceptance = new LexicographicSaAcceptance(
                saConfig,
                calibration,
                coolingEvaluations,
                searchRandom,
                initial.score().isFeasible());

        SearchState state = new SearchState(problem, initial);
        IncrementalScoreCalculator incremental = new IncrementalScoreCalculator(
                problem, initial, fullScoreCalculator);
        AlnsIteration iteration = new AlnsIteration(
                problem, state, incremental, compatibilityMatrix);

        long iterations = 0L;
        long evaluations = 0L;
        long acceptedCandidates = 0L;
        long rejectedCandidates = 0L;
        long destroyFailures = 0L;
        long repairFailures = 0L;
        long operatorExceptions = 0L;
        long scoreMismatchFailures = 0L;
        long stateCorruptionFailures = 0L;
        long lastBestImprovementEvaluation = 0L;
        long initialWorseningCandidates = 0L;
        long initialAcceptedWorseningCandidates = 0L;
        List<AlnsRunMetrics.BestImprovement> bestImprovements = new ArrayList<>();
        TerminationReason reason = null;

        while (reason == null) {
            reason = requestedTermination(options, searchDeadlineNanos);
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
                    && evaluations - lastBestImprovementEvaluation
                            >= options.maxStagnantEvaluations()) {
                reason = TerminationReason.CONVERGED;
                break;
            }

            AdaptiveOperatorSelector.Selection selected = selector.select();
            RosterScore currentBefore = state.score();
            RosterScore bestBefore = bestOverall.score();
            try {
                AlnsIterationResult iterationResult = iteration.execute(
                        selected.destroyOperator(),
                        selected.repairOperator(),
                        acceptance,
                        config.iterationConfig(),
                        searchRandom,
                        () -> requestedTermination(options, searchDeadlineNanos) != null);
                iterations++;

                if (iterationResult.status() == AlnsIterationStatus.CANCELLED) {
                    reason = Objects.requireNonNullElse(
                            requestedTermination(options, searchDeadlineNanos),
                            TerminationReason.CANCELLED);
                    break;
                }
                if (iterationResult.status() == AlnsIterationStatus.NO_MUTABLE_SHIFT) {
                    reason = TerminationReason.CONVERGED;
                    break;
                }
                if (iterationResult.status() == AlnsIterationStatus.DESTROY_FAILED) {
                    destroyFailures++;
                } else if (iterationResult.status() == AlnsIterationStatus.REPAIR_FAILED) {
                    repairFailures++;
                } else if (iterationResult.status() == AlnsIterationStatus.OPERATOR_EXCEPTION) {
                    operatorExceptions++;
                }
                if (iterationResult.candidateScore() == null) {
                    continue;
                }

                evaluations++;
                LexicographicSaAcceptance.Decision saDecision = acceptance.lastDecision();
                boolean initialSegment = evaluations <= config.adaptiveConfig().segmentLength();
                boolean worseningDecision = saDecision.type()
                        == LexicographicSaAcceptance.DecisionType.HARD_WORSENING_SA
                        || saDecision.type()
                                == LexicographicSaAcceptance.DecisionType.SOFT_WORSENING_SA;
                if (initialSegment && worseningDecision) {
                    initialWorseningCandidates++;
                    if (saDecision.accepted()) {
                        initialAcceptedWorseningCandidates++;
                    }
                }

                OperatorOutcome outcome;
                if (iterationResult.status() == AlnsIterationStatus.ACCEPTED) {
                    acceptedCandidates++;
                    RosterSolution current = state.snapshot();
                    if (current.score().compareTo(bestBefore) > 0) {
                        bestOverall = current;
                        lastBestImprovementEvaluation = evaluations;
                        outcome = OperatorOutcome.GLOBAL_BEST;
                        bestImprovements.add(new AlnsRunMetrics.BestImprovement(
                                evaluations,
                                current.score(),
                                selected.destroyOperator().id(),
                                selected.repairOperator().id()));
                        if (current.score().isFeasible()
                                && (verifiedFeasibleBest == null
                                        || current.score().compareTo(verifiedFeasibleBest.score()) > 0)) {
                            verifiedFeasibleBest = current;
                            notifyListenerSafely(listener, verifiedFeasibleBest);
                        }
                    } else if (current.score().compareTo(currentBefore) > 0) {
                        outcome = OperatorOutcome.CURRENT_IMPROVEMENT;
                    } else {
                        outcome = OperatorOutcome.ACCEPTED_WORSENING;
                    }
                } else {
                    rejectedCandidates++;
                    outcome = OperatorOutcome.REJECTED;
                }
                selector.recordOutcome(selected, outcome);
            } catch (ScoreMismatchException mismatch) {
                scoreMismatchFailures++;
                LOG.error("POJO_ALNS full/incremental score mismatch", mismatch);
                verifiedFeasibleBest = betterVerifiedFeasible(
                        verifiedFeasibleBest, mismatch.lastVerifiedBest());
                reason = TerminationReason.SCORE_MISMATCH;
            } catch (StateCorruptionException corruption) {
                stateCorruptionFailures++;
                LOG.error("POJO_ALNS state corruption", corruption);
                verifiedFeasibleBest = betterVerifiedFeasible(
                        verifiedFeasibleBest, corruption.lastVerifiedBest());
                reason = TerminationReason.STATE_CORRUPTION;
            }
        }

        selector.finishSegment();
        if (reason == null) {
            reason = TerminationReason.COMPLETED;
        }

        boolean finalValidationPerformed = false;
        if (verifiedFeasibleBest != null) {
            finalValidationPerformed = true;
            RosterScore finalScore = fullScoreCalculator.calculateScore(problem, verifiedFeasibleBest);
            if (!finalScore.equals(verifiedFeasibleBest.score())) {
                scoreMismatchFailures++;
                LOG.error("POJO_ALNS final score mismatch: stored={}, full={}",
                        verifiedFeasibleBest.score(), finalScore);
                reason = TerminationReason.SCORE_MISMATCH;
            } else if (!pinnedAssignmentsIntact(problem, verifiedFeasibleBest)) {
                stateCorruptionFailures++;
                LOG.error("POJO_ALNS final pinned assignment mismatch");
                reason = TerminationReason.STATE_CORRUPTION;
            }
        } else if (isNormalCompletion(reason)) {
            reason = TerminationReason.NO_FEASIBLE_SOLUTION;
        }

        AlnsRunMetrics metrics = metrics(
                profile,
                options.randomSeed(),
                initialFeasibilitySeed,
                initialFeasibilityEvaluationCount,
                calibration,
                iterations,
                evaluations,
                acceptedCandidates,
                rejectedCandidates,
                destroyFailures,
                repairFailures,
                operatorExceptions,
                scoreMismatchFailures,
                stateCorruptionFailures,
                initialWorseningCandidates,
                initialAcceptedWorseningCandidates,
                selector,
                bestImprovements,
                finalValidationPerformed,
                reason);
        return result(
                verifiedFeasibleBest,
                reason,
                iterations,
                evaluations,
                startedNanos,
                options.randomSeed(),
                metrics);
    }

    private AlnsSolverConfig runtimeConfig() {
        if (fixedConfig != null) {
            return fixedConfig;
        }
        return new AlnsSolverConfig(
                new AlnsIterationConfig(
                        destroyRate, qMin, qMax, absoluteRemovalLimit, maxRepairAttempts),
                new SaAcceptanceConfig(
                        targetInitialAcceptance,
                        finalTemperatureRatio,
                        fallbackCoolingEvaluations),
                new AdaptiveOperatorConfig(
                        initialWeight,
                        minimumWeight,
                        reactionFactor,
                        segmentLength,
                        globalBestReward,
                        currentImprovementReward,
                        acceptedWorseningReward,
                        rejectedReward),
                initialFeasibilityEvaluations,
                calibrationAttempts,
                Duration.ofMillis(finalValidationReserveMillis));
    }

    private Optional<RosterSolution> verifiedWarmStart(
            PlanningProblem problem, SolveOptions options) {
        if (options.warmStart().isEmpty()) {
            return Optional.empty();
        }
        RosterSolution warmStart = options.warmStart().orElseThrow();
        if (warmStart.employeeCount() != problem.employeeCount()
                || warmStart.shiftCount() != problem.shiftCount()
                || !pinnedAssignmentsIntact(problem, warmStart)) {
            return Optional.empty();
        }
        RosterScore verified = fullScoreCalculator.calculateScore(problem, warmStart);
        return Optional.of(new RosterSolution(
                problem.employeeCount(), warmStart.employeeIndexByShift(), verified));
    }

    private static boolean pinnedAssignmentsIntact(
            PlanningProblem problem, RosterSolution solution) {
        for (int shiftIndex : problem.pinnedShiftIndexes()) {
            if (solution.employeeIndex(shiftIndex) != problem.initialEmployeeIndex(shiftIndex)) {
                return false;
            }
        }
        return true;
    }

    private static void validateTermination(SolveOptions options) {
        if (options.spentLimit().isEmpty()
                && !options.hasDeadline()
                && options.maxEvaluations() == SolveOptions.UNLIMITED
                && options.maxIterations() == SolveOptions.UNLIMITED
                && options.maxStagnantEvaluations() == SolveOptions.UNLIMITED) {
            throw new IllegalArgumentException("POJO_ALNS에는 하나 이상의 종료 조건이 필요합니다.");
        }
    }

    private static long effectiveDeadline(long startedNanos, SolveOptions options) {
        long result = options.deadlineNanos();
        if (options.spentLimit().isPresent()) {
            long spentDeadline = saturatedAdd(
                    startedNanos, options.spentLimit().orElseThrow().toNanos());
            if (result == SolveOptions.NO_DEADLINE
                    || spentDeadline - startedNanos < result - startedNanos) {
                result = spentDeadline;
            }
        }
        return result;
    }

    private static long reserveFinalValidationTime(
            long startedNanos, long deadlineNanos, Duration reserve) {
        if (deadlineNanos == SolveOptions.NO_DEADLINE || reserve.isZero()) {
            return deadlineNanos;
        }
        long reserveNanos = reserve.toNanos();
        long availableNanos = deadlineNanos - startedNanos;
        if (availableNanos <= reserveNanos) {
            return startedNanos;
        }
        return deadlineNanos - reserveNanos;
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

    private static RosterSolution betterVerifiedFeasible(
            RosterSolution first, RosterSolution second) {
        if (second == null || !second.score().isFeasible()) {
            return first;
        }
        return first == null || second.score().compareTo(first.score()) > 0 ? second : first;
    }

    private static void notifyListenerSafely(
            SolveListener listener, RosterSolution solution) {
        try {
            listener.onBestSolution(solution);
        } catch (RuntimeException listenerFailure) {
            LOG.warn("SolveListener failed but POJO_ALNS search continues", listenerFailure);
        }
    }

    private static String profile(SolveOptions options) {
        return options.spentLimit().isPresent() || options.hasDeadline()
                ? "WALL_CLOCK_PRODUCTION"
                : "FIXED_EVALUATION_DETERMINISTIC";
    }

    private SolveResult<RosterSolution> emptyResult(
            TerminationReason reason,
            long iterations,
            long evaluations,
            long startedNanos,
            long seed,
            String profile,
            AlnsSolverConfig config) {
        return emptyResult(
                reason, iterations, evaluations, startedNanos, seed, profile, config,
                deriveInitialFeasibilitySeed(seed), 0L);
    }

    private SolveResult<RosterSolution> emptyResult(
            TerminationReason reason,
            long iterations,
            long evaluations,
            long startedNanos,
            long seed,
            String profile,
            AlnsSolverConfig config,
            long initialFeasibilitySeed,
            long initialFeasibilityEvaluationCount) {
        SaCalibrationResult calibration = SaCalibrationResult.fallback(
                SaCalibrator.deriveSeed(seed),
                config.saConfig().targetInitialAcceptanceProbability());
        AlnsRunMetrics metrics = new AlnsRunMetrics(
                profile,
                seed,
                initialFeasibilitySeed,
                initialFeasibilityEvaluationCount,
                calibration,
                iterations,
                evaluations,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                List.of(),
                List.of(),
                List.of(),
                false,
                reason);
        return result(null, reason, iterations, evaluations, startedNanos, seed, metrics);
    }

    private static AlnsRunMetrics metrics(
            String profile,
            long seed,
            long initialFeasibilitySeed,
            long initialFeasibilityEvaluationCount,
            SaCalibrationResult calibration,
            long iterations,
            long evaluations,
            long acceptedCandidates,
            long rejectedCandidates,
            long destroyFailures,
            long repairFailures,
            long operatorExceptions,
            long scoreMismatchFailures,
            long stateCorruptionFailures,
            long initialWorseningCandidates,
            long initialAcceptedWorseningCandidates,
            AdaptiveOperatorSelector selector,
            List<AlnsRunMetrics.BestImprovement> bestImprovements,
            boolean finalValidationPerformed,
            TerminationReason reason) {
        return new AlnsRunMetrics(
                profile,
                seed,
                initialFeasibilitySeed,
                initialFeasibilityEvaluationCount,
                calibration,
                iterations,
                evaluations,
                acceptedCandidates,
                rejectedCandidates,
                destroyFailures,
                repairFailures,
                operatorExceptions,
                scoreMismatchFailures,
                stateCorruptionFailures,
                initialWorseningCandidates,
                initialAcceptedWorseningCandidates,
                selector.completedSegments(),
                selector.destroyStatistics(),
                selector.repairStatistics(),
                bestImprovements,
                finalValidationPerformed,
                reason);
    }

    private static SolveResult<RosterSolution> result(
            RosterSolution solution,
            TerminationReason reason,
            long iterations,
            long evaluations,
            long startedNanos,
            long seed,
            AlnsRunMetrics metrics) {
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

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    static long deriveInitialFeasibilitySeed(long searchSeed) {
        long value = searchSeed ^ 0x494E49545F464541L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
