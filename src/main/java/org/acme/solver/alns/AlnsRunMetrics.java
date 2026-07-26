package org.acme.solver.alns;

import java.util.List;
import java.util.Objects;

import org.acme.solver.core.RosterScore;
import org.acme.solver.core.SolveMetrics;
import org.acme.solver.core.TerminationReason;

/** POJO_ALNS가 결과와 함께 노출하는 calibration/operator/best 메트릭입니다. */
public record AlnsRunMetrics(
        String profile,
        long searchSeed,
        long initialFeasibilitySeed,
        long initialFeasibilityEvaluations,
        SaCalibrationResult calibration,
        long searchIterations,
        long searchEvaluations,
        long acceptedCandidates,
        long rejectedCandidates,
        long destroyFailures,
        long repairFailures,
        long operatorExceptions,
        long scoreMismatchFailures,
        long stateCorruptionFailures,
        long initialWorseningCandidates,
        long initialAcceptedWorseningCandidates,
        long completedWeightSegments,
        List<OperatorStatistics> destroyOperators,
        List<OperatorStatistics> repairOperators,
        List<BestImprovement> bestImprovements,
        boolean finalValidationPerformed,
        TerminationReason terminationReason) implements SolveMetrics {

    public AlnsRunMetrics {
        if (profile == null || profile.isBlank()) {
            throw new IllegalArgumentException("profile은 비어 있을 수 없습니다.");
        }
        Objects.requireNonNull(calibration, "calibration");
        Objects.requireNonNull(destroyOperators, "destroyOperators");
        Objects.requireNonNull(repairOperators, "repairOperators");
        Objects.requireNonNull(bestImprovements, "bestImprovements");
        Objects.requireNonNull(terminationReason, "terminationReason");
        destroyOperators = List.copyOf(destroyOperators);
        repairOperators = List.copyOf(repairOperators);
        bestImprovements = List.copyOf(bestImprovements);
        if (initialFeasibilityEvaluations < 0L
                || searchIterations < 0L || searchEvaluations < 0L
                || acceptedCandidates < 0L || rejectedCandidates < 0L
                || destroyFailures < 0L || repairFailures < 0L
                || operatorExceptions < 0L || scoreMismatchFailures < 0L
                || stateCorruptionFailures < 0L
                || initialWorseningCandidates < 0L
                || initialAcceptedWorseningCandidates < 0L
                || initialAcceptedWorseningCandidates > initialWorseningCandidates
                || completedWeightSegments < 0L) {
            throw new IllegalArgumentException("ALNS metric count는 음수이거나 모순일 수 없습니다.");
        }
    }

    @Override
    public String engineId() {
        return "POJO_ALNS";
    }

    public double observedInitialWorseningAcceptanceRate() {
        return initialWorseningCandidates == 0L
                ? 0.0d
                : (double) initialAcceptedWorseningCandidates / (double) initialWorseningCandidates;
    }

    /** reject 또는 실패 후 원자적 rollback이 시도된 iteration 수입니다. */
    public long rollbackAttemptCount() {
        return rejectedCandidates + destroyFailures + repairFailures + operatorExceptions;
    }

    /** rollback 검증 실패로 상태 오염이 감지된 횟수입니다. */
    public long rollbackFailureCount() {
        return stateCorruptionFailures;
    }

    public record BestImprovement(
            long evaluation,
            RosterScore score,
            String destroyOperatorId,
            String repairOperatorId) {

        public BestImprovement {
            if (evaluation < 0L) {
                throw new IllegalArgumentException("evaluation은 음수일 수 없습니다.");
            }
            Objects.requireNonNull(score, "score");
            Objects.requireNonNull(destroyOperatorId, "destroyOperatorId");
            Objects.requireNonNull(repairOperatorId, "repairOperatorId");
        }
    }
}
