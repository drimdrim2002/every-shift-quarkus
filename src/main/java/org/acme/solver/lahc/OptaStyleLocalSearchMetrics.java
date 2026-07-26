package org.acme.solver.lahc;

import java.util.List;
import java.util.Objects;

import org.acme.solver.core.RosterScore;
import org.acme.solver.core.SolveMetrics;
import org.acme.solver.core.TerminationReason;

/** test-only Opta-style local-move 비교군의 관측 메트릭입니다. */
public record OptaStyleLocalSearchMetrics(
        int historyLength,
        int fullVerificationInterval,
        long evaluatedCandidates,
        long acceptedSteps,
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
        long initialFeasibilityEvaluations,
        List<BestImprovement> bestImprovements,
        boolean finalValidationPerformed,
        TerminationReason terminationReason) implements SolveMetrics {

    public OptaStyleLocalSearchMetrics {
        if (historyLength <= 0
                || fullVerificationInterval <= 0
                || evaluatedCandidates < 0L
                || acceptedSteps < 0L
                || rejectedCandidates < 0L
                || cancelledCandidates < 0L
                || changeCandidates < 0L
                || swapCandidates < 0L
                || acceptedChangeSteps < 0L
                || acceptedSwapSteps < 0L
                || rejectedChangeCandidates < 0L
                || rejectedSwapCandidates < 0L
                || scoreMismatchFailures < 0L
                || stateCorruptionFailures < 0L
                || fullVerificationCount < 0L
                || initialFeasibilityEvaluations < 0L
                || acceptedSteps > evaluatedCandidates
                || acceptedSteps + rejectedCandidates + cancelledCandidates != evaluatedCandidates
                || changeCandidates + swapCandidates != evaluatedCandidates
                || acceptedChangeSteps + acceptedSwapSteps != acceptedSteps
                || rejectedChangeCandidates + rejectedSwapCandidates != rejectedCandidates) {
            throw new IllegalArgumentException("Opta-style local search metric count가 모순됩니다.");
        }
        Objects.requireNonNull(bestImprovements, "bestImprovements");
        Objects.requireNonNull(terminationReason, "terminationReason");
        bestImprovements = List.copyOf(bestImprovements);
    }

    @Override
    public String engineId() {
        return "POJO_OPTA_STYLE_LOCAL_MOVE";
    }

    public long rollbackAttemptCount() {
        return rejectedCandidates + cancelledCandidates;
    }

    public long rollbackFailureCount() {
        return stateCorruptionFailures;
    }

    public record BestImprovement(long evaluation, RosterScore score, String moveType) {

        public BestImprovement {
            if (evaluation < 0L) {
                throw new IllegalArgumentException("evaluation은 음수일 수 없습니다.");
            }
            Objects.requireNonNull(score, "score");
            Objects.requireNonNull(moveType, "moveType");
        }
    }
}
