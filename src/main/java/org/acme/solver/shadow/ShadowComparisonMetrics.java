package org.acme.solver.shadow;

import java.util.List;
import java.util.Objects;

import org.acme.solver.core.RosterScore;
import org.acme.solver.core.SolveMetrics;
import org.acme.solver.core.TerminationReason;

/** primary 반환을 보존하면서 두 엔진의 품질·무결성·탐색 상태를 기록하는 Phase 7 관측값입니다. */
public record ShadowComparisonMetrics(
        SolverMode mode,
        String candidateFingerprint,
        String configFingerprint,
        EngineObservation primary,
        EngineObservation shadow,
        PojoOutcome pojoOutcome,
        String firstDifferenceObjective,
        long assignmentDifferenceCount,
        String shadowFailureClass,
        String shadowFailureMessage) implements SolveMetrics {

    public ShadowComparisonMetrics {
        Objects.requireNonNull(mode, "mode");
        requireText(candidateFingerprint, "candidateFingerprint");
        requireText(configFingerprint, "configFingerprint");
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(pojoOutcome, "pojoOutcome");
        Objects.requireNonNull(firstDifferenceObjective, "firstDifferenceObjective");
        if (assignmentDifferenceCount < -1L) {
            throw new IllegalArgumentException("assignmentDifferenceCount는 -1 이상이어야 합니다.");
        }
        if (!mode.hasShadow() && shadow != null) {
            throw new IllegalArgumentException("ONLY mode에는 shadow 관측값이 없어야 합니다.");
        }
    }

    @Override
    public String engineId() {
        return "PHASE7_SHADOW_COORDINATOR";
    }

    public enum PojoOutcome {
        WIN,
        TIE,
        LOSS,
        NOT_COMPARED
    }

    public record EngineObservation(
            SolverMode.EngineRole role,
            String engineId,
            long elapsedMillis,
            TerminationReason terminationReason,
            long seed,
            RosterScore score,
            boolean complete,
            boolean fullScoreVerified,
            long pinnedAssignmentChangeAttempts,
            long fullScoreMismatchCount,
            long incrementalScoreMismatchCount,
            long initialSolutionFailureCount,
            long repairFailureCount,
            long rollbackAttemptCount,
            long rollbackFailureCount,
            long stateCorruptionCount,
            long timeoutCount,
            List<OperatorObservation> operators) {

        public EngineObservation {
            Objects.requireNonNull(role, "role");
            requireText(engineId, "engineId");
            Objects.requireNonNull(terminationReason, "terminationReason");
            operators = List.copyOf(Objects.requireNonNull(operators, "operators"));
            if (elapsedMillis < 0L || pinnedAssignmentChangeAttempts < 0L
                    || fullScoreMismatchCount < 0L || incrementalScoreMismatchCount < 0L
                    || initialSolutionFailureCount < 0L || repairFailureCount < 0L
                    || rollbackAttemptCount < 0L || rollbackFailureCount < 0L
                    || stateCorruptionCount < 0L || timeoutCount < 0L) {
                throw new IllegalArgumentException("engine 관측 count는 음수일 수 없습니다.");
            }
        }
    }

    public record OperatorObservation(
            String operatorId,
            long selectionCount,
            long acceptedCount,
            long rejectedCount,
            long globalBestContributionCount) {

        public OperatorObservation {
            requireText(operatorId, "operatorId");
            if (selectionCount < 0L || acceptedCount < 0L || rejectedCount < 0L
                    || globalBestContributionCount < 0L) {
                throw new IllegalArgumentException("operator 관측 count는 음수일 수 없습니다.");
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "는 비어 있을 수 없습니다.");
        }
    }
}
