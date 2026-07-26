package org.acme.solver.shadow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.acme.solver.alns.AlnsRunMetrics;
import org.acme.solver.alns.OperatorStatistics;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveListener;
import org.acme.solver.core.SolveMetrics;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.core.SolverEngine;
import org.acme.solver.core.TerminationReason;
import org.acme.solver.lahc.AlnsChangeSwapVndHybridMetrics;
import org.acme.solver.lahc.ExhaustivePrefixReassignIntensificationMetrics;
import org.acme.solver.lahc.FairnessRestrictedLocalSearchMetrics;
import org.acme.solver.lahc.OptaStyleLocalSearchMetrics;
import org.acme.solver.lahc.OrderedVndLocalSearchMetrics;
import org.acme.solver.score.FullScoreCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * primary 결과만 반환하고 shadow 결과는 구조화된 비교 메트릭과 로그로만 관측합니다.
 *
 * <p>primary 실패 시 shadow로 fallback하지 않습니다. shadow 예외도 숨기지 않고 메트릭과 error log에
 * 남기되 이미 선택된 primary의 assignment/score를 바꾸지 않습니다.</p>
 */
public final class ShadowSolverCoordinator implements SolverEngine {

    private static final Logger LOG = LoggerFactory.getLogger(ShadowSolverCoordinator.class);

    private final SolverMode mode;
    private final SolverEngine optaPlanner;
    private final SolverEngine pojo;
    private final FullScoreCalculator full;

    public ShadowSolverCoordinator(SolverMode mode, SolverEngine optaPlanner, SolverEngine pojo) {
        this(mode, optaPlanner, pojo, new FullScoreCalculator());
    }

    ShadowSolverCoordinator(
            SolverMode mode, SolverEngine optaPlanner, SolverEngine pojo, FullScoreCalculator full) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.optaPlanner = Objects.requireNonNull(optaPlanner, "optaPlanner");
        this.pojo = Objects.requireNonNull(pojo, "pojo");
        this.full = Objects.requireNonNull(full, "full");
    }

    public SolverMode mode() {
        return mode;
    }

    @Override
    public SolveResult<RosterSolution> solve(
            PlanningProblem problem, SolveOptions options, SolveListener listener) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(listener, "listener");
        String configFingerprint = SolverConfigFingerprint.invocationFingerprint(mode, options);

        SolverEngine primaryEngine = engine(mode.primary());
        SolveResult<RosterSolution> primaryResult = primaryEngine.solve(problem, options, listener);
        Observation primary = observe(mode.primary(), problem, primaryResult);

        SolveResult<RosterSolution> shadowResult = null;
        Observation shadow = null;
        RuntimeException shadowFailure = null;
        if (mode.hasShadow()) {
            try {
                shadowResult = engine(mode.shadow()).solve(problem, options, SolveListener.noop());
                shadow = observe(mode.shadow(), problem, shadowResult);
            } catch (RuntimeException failure) {
                shadowFailure = failure;
                LOG.error("solver_shadow_failure mode={} role={} configFingerprint={} errorClass={} message={}",
                        mode, mode.shadow(), configFingerprint, failure.getClass().getName(), failure.getMessage(),
                        failure);
            }
        }

        Comparison comparison = compare(primaryResult, shadowResult);
        ShadowComparisonMetrics metrics = new ShadowComparisonMetrics(
                mode,
                SolverConfigFingerprint.candidateFingerprint(),
                configFingerprint,
                primary.value(),
                shadow == null ? null : shadow.value(),
                comparison.pojoOutcome(),
                comparison.firstDifferenceObjective(),
                comparison.assignmentDifferenceCount(),
                shadowFailure == null ? null : shadowFailure.getClass().getName(),
                shadowFailure == null ? null : Objects.toString(shadowFailure.getMessage(), ""));

        LOG.info("solver_shadow_comparison mode={} returnedRole={} observedRole={} outcome={} firstDifference={} "
                        + "assignmentDiff={} primaryScore={} shadowScore={} seed={} candidateFingerprint={} "
                        + "configFingerprint={} primaryFullMismatch={} primaryIncrementalMismatch={} "
                        + "shadowFullMismatch={} shadowIncrementalMismatch={} primaryPinned={} shadowPinned={}",
                mode,
                mode.primary(),
                mode.shadow(),
                comparison.pojoOutcome(),
                comparison.firstDifferenceObjective(),
                comparison.assignmentDifferenceCount(),
                primaryResult.score(),
                shadowResult == null ? null : shadowResult.score(),
                options.randomSeed(),
                SolverConfigFingerprint.candidateFingerprint(),
                configFingerprint,
                primary.value().fullScoreMismatchCount(),
                primary.value().incrementalScoreMismatchCount(),
                shadow == null ? -1L : shadow.value().fullScoreMismatchCount(),
                shadow == null ? -1L : shadow.value().incrementalScoreMismatchCount(),
                primary.value().pinnedAssignmentChangeAttempts(),
                shadow == null ? -1L : shadow.value().pinnedAssignmentChangeAttempts());

        TerminationReason returnedReason = primaryResult.terminationReason();
        if (primaryResult.bestSolution() != null
                && (!primary.value().complete()
                        || primary.value().pinnedAssignmentChangeAttempts() > 0L)) {
            returnedReason = TerminationReason.STATE_CORRUPTION;
        } else if (primaryResult.bestSolution() != null && !primary.value().fullScoreVerified()) {
            returnedReason = TerminationReason.SCORE_MISMATCH;
        }
        return new SolveResult<>(
                primaryResult.bestSolution(),
                primaryResult.score(),
                returnedReason,
                primaryResult.iterations(),
                primaryResult.evaluationCount(),
                primaryResult.elapsedMillis(),
                primaryResult.seed(),
                metrics);
    }

    private SolverEngine engine(SolverMode.EngineRole role) {
        return role == SolverMode.EngineRole.OPTAPLANNER ? optaPlanner : pojo;
    }

    private Observation observe(
            SolverMode.EngineRole role,
            PlanningProblem problem,
            SolveResult<RosterSolution> result) {
        RosterSolution solution = result.bestSolution();
        boolean complete = solution != null
                && solution.employeeCount() == problem.employeeCount()
                && solution.shiftCount() == problem.shiftCount();
        long pinnedChanges = complete ? pinnedChanges(problem, solution) : 0L;
        boolean fullVerified = complete
                && result.score() != null
                && result.score().equals(solution.score())
                && result.score().equals(full.calculateScore(problem, solution));

        MetricCounts counts = new MetricCounts();
        collect(result.metrics(), "", counts, true);
        if (result.terminationReason() == TerminationReason.INITIAL_SOLUTION_FAILED) {
            counts.initialFailures++;
        }
        if (result.terminationReason() == TerminationReason.STATE_CORRUPTION) {
            counts.stateCorruptions++;
        }
        if (result.terminationReason() == TerminationReason.DEADLINE_REACHED
                && counts.timeouts == 0L) {
            counts.timeouts++;
        }
        String engineId = role == SolverMode.EngineRole.OPTAPLANNER
                ? "OPTAPLANNER" : "POJO_PHASE6_FINAL";
        return new Observation(new ShadowComparisonMetrics.EngineObservation(
                role,
                engineId,
                result.elapsedMillis(),
                result.terminationReason(),
                result.seed(),
                result.score(),
                complete,
                fullVerified,
                pinnedChanges,
                !fullVerified && solution != null ? 1L : 0L,
                counts.scoreMismatches,
                counts.initialFailures,
                counts.repairFailures,
                counts.rollbackAttempts,
                counts.rollbackFailures,
                counts.stateCorruptions,
                counts.timeouts,
                counts.operators));
    }

    private static long pinnedChanges(PlanningProblem problem, RosterSolution solution) {
        long changes = 0L;
        for (int shiftIndex : problem.pinnedShiftIndexes()) {
            if (solution.employeeIndex(shiftIndex) != problem.initialEmployeeIndex(shiftIndex)) {
                changes++;
            }
        }
        return changes;
    }

    private Comparison compare(
            SolveResult<RosterSolution> primary, SolveResult<RosterSolution> shadow) {
        if (shadow == null || primary.score() == null || shadow.score() == null) {
            return Comparison.NOT_COMPARED;
        }
        RosterScore pojoScore = mode.primary() == SolverMode.EngineRole.POJO
                ? primary.score() : shadow.score();
        RosterScore optaScore = mode.primary() == SolverMode.EngineRole.OPTAPLANNER
                ? primary.score() : shadow.score();
        int comparison = pojoScore.compareTo(optaScore);
        ShadowComparisonMetrics.PojoOutcome outcome = comparison > 0
                ? ShadowComparisonMetrics.PojoOutcome.WIN
                : comparison < 0
                        ? ShadowComparisonMetrics.PojoOutcome.LOSS
                        : ShadowComparisonMetrics.PojoOutcome.TIE;
        String firstDifference = firstDifference(pojoScore, optaScore);
        long assignmentDiff = assignmentDifferences(primary.bestSolution(), shadow.bestSolution());
        return new Comparison(outcome, firstDifference, assignmentDiff);
    }

    private static String firstDifference(RosterScore pojo, RosterScore opta) {
        if (pojo.hardScore() != opta.hardScore()) {
            return "hard";
        }
        for (int index = 0; index < RosterScore.SOFT_LEVELS; index++) {
            if (pojo.softScore(index) != opta.softScore(index)) {
                return "soft[" + index + "]";
            }
        }
        return "tie";
    }

    private static long assignmentDifferences(RosterSolution first, RosterSolution second) {
        if (first == null || second == null || first.shiftCount() != second.shiftCount()) {
            return -1L;
        }
        long differences = 0L;
        for (int shiftIndex = 0; shiftIndex < first.shiftCount(); shiftIndex++) {
            if (first.employeeIndex(shiftIndex) != second.employeeIndex(shiftIndex)) {
                differences++;
            }
        }
        return differences;
    }

    private static void collect(
            SolveMetrics metrics, String prefix, MetricCounts counts, boolean includeIntegrityCounts) {
        if (metrics instanceof AlnsRunMetrics alns) {
            counts.repairFailures += alns.repairFailures();
            if (includeIntegrityCounts) {
                counts.scoreMismatches += alns.scoreMismatchFailures();
                counts.stateCorruptions += alns.stateCorruptionFailures();
                counts.rollbackFailures += alns.rollbackFailureCount();
            }
            counts.rollbackAttempts += alns.rollbackAttemptCount();
            if (alns.terminationReason() == TerminationReason.DEADLINE_REACHED) {
                counts.timeouts++;
            }
            addOperators(prefix + "destroy/", alns.destroyOperators(), counts);
            addOperators(prefix + "repair/", alns.repairOperators(), counts);
        } else if (metrics instanceof AlnsChangeSwapVndHybridMetrics hybrid) {
            if (includeIntegrityCounts) {
                counts.scoreMismatches += hybrid.scoreMismatchFailures();
                counts.stateCorruptions += hybrid.stateCorruptionFailures();
                counts.rollbackFailures += hybrid.rollbackFailureCount();
            }
            for (AlnsChangeSwapVndHybridMetrics.Stage stage : hybrid.stages()) {
                collect(stage.stageMetrics(), prefix + stage.stageId() + "/", counts, false);
            }
        } else if (metrics instanceof OrderedVndLocalSearchMetrics vnd) {
            if (includeIntegrityCounts) {
                counts.scoreMismatches += vnd.scoreMismatchFailures();
                counts.stateCorruptions += vnd.stateCorruptionFailures();
                counts.rollbackFailures += vnd.rollbackFailureCount();
            }
            counts.rollbackAttempts += vnd.rollbackAttemptCount();
            if (vnd.terminationReason() == TerminationReason.DEADLINE_REACHED) {
                counts.timeouts++;
            }
            counts.operators.add(new ShadowComparisonMetrics.OperatorObservation(
                    prefix + "Reassign",
                    vnd.evaluatedReassignCandidates(),
                    vnd.acceptedReassignCandidates(),
                    vnd.evaluatedReassignCandidates() - vnd.acceptedReassignCandidates(),
                    vnd.bestImprovements().stream()
                            .filter(item -> "REASSIGN".equals(item.neighborhood())).count()));
            counts.operators.add(new ShadowComparisonMetrics.OperatorObservation(
                    prefix + "Swap",
                    vnd.evaluatedSwapCandidates(),
                    vnd.acceptedSwapCandidates(),
                    vnd.evaluatedSwapCandidates() - vnd.acceptedSwapCandidates(),
                    vnd.bestImprovements().stream()
                            .filter(item -> "SWAP".equals(item.neighborhood())).count()));
        } else if (metrics instanceof ExhaustivePrefixReassignIntensificationMetrics exhaustive) {
            if (includeIntegrityCounts) {
                counts.scoreMismatches += exhaustive.scoreMismatchFailures();
                counts.stateCorruptions += exhaustive.stateCorruptionFailures();
                counts.rollbackFailures += exhaustive.rollbackFailureCount();
            }
            counts.rollbackAttempts += exhaustive.rollbackAttemptCount();
            if (exhaustive.terminationReason() == TerminationReason.DEADLINE_REACHED) {
                counts.timeouts++;
            }
            counts.operators.add(new ShadowComparisonMetrics.OperatorObservation(
                    prefix + "PrefixReassign",
                    exhaustive.evaluatedCandidates(),
                    exhaustive.acceptedCandidates(),
                    exhaustive.rejectedCandidates(),
                    exhaustive.bestImprovements().size()));
        } else if (metrics instanceof FairnessRestrictedLocalSearchMetrics fairness) {
            if (includeIntegrityCounts) {
                counts.scoreMismatches += fairness.scoreMismatchFailures();
                counts.stateCorruptions += fairness.stateCorruptionFailures();
                counts.rollbackFailures += fairness.rollbackFailureCount();
            }
            counts.rollbackAttempts += fairness.rollbackAttemptCount();
            if (fairness.terminationReason() == TerminationReason.DEADLINE_REACHED) {
                counts.timeouts++;
            }
        } else if (metrics instanceof OptaStyleLocalSearchMetrics local) {
            if (includeIntegrityCounts) {
                counts.scoreMismatches += local.scoreMismatchFailures();
                counts.stateCorruptions += local.stateCorruptionFailures();
                counts.rollbackFailures += local.rollbackFailureCount();
            }
            counts.rollbackAttempts += local.rollbackAttemptCount();
            if (local.terminationReason() == TerminationReason.DEADLINE_REACHED) {
                counts.timeouts++;
            }
        }
    }

    private static void addOperators(
            String prefix, List<OperatorStatistics> statistics, MetricCounts counts) {
        for (OperatorStatistics operator : statistics) {
            counts.operators.add(new ShadowComparisonMetrics.OperatorObservation(
                    prefix + operator.operatorId(),
                    operator.selectionCount(),
                    operator.globalBestCount()
                            + operator.currentImprovementCount()
                            + operator.acceptedWorseningCount(),
                    operator.rejectionCount(),
                    operator.globalBestCount()));
        }
    }

    private record Observation(ShadowComparisonMetrics.EngineObservation value) {
    }

    private record Comparison(
            ShadowComparisonMetrics.PojoOutcome pojoOutcome,
            String firstDifferenceObjective,
            long assignmentDifferenceCount) {
        private static final Comparison NOT_COMPARED = new Comparison(
                ShadowComparisonMetrics.PojoOutcome.NOT_COMPARED, "not-compared", -1L);
    }

    private static final class MetricCounts {
        private long scoreMismatches;
        private long initialFailures;
        private long repairFailures;
        private long rollbackAttempts;
        private long rollbackFailures;
        private long stateCorruptions;
        private long timeouts;
        private final List<ShadowComparisonMetrics.OperatorObservation> operators = new ArrayList<>();
    }
}
