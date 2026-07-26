package org.acme.solver.lahc;

import java.util.List;
import java.util.Objects;

import org.acme.solver.core.RosterScore;
import org.acme.solver.core.SolveMetrics;
import org.acme.solver.core.TerminationReason;

/** 상위 목적식을 보존하면서 soft[2]만 개선하는 연구 lane의 관측값입니다. */
public record FairnessRestrictedLocalSearchMetrics(
        long evaluatedCandidates,
        long acceptedCandidates,
        long rejectedCandidates,
        long changeCandidates,
        long swapCandidates,
        long acceptedChangeCandidates,
        long acceptedSwapCandidates,
        long scoreMismatchFailures,
        long stateCorruptionFailures,
        long fullVerificationCount,
        long initialFeasibilityEvaluations,
        FairnessSelectorMetrics selectorMetrics,
        List<BestImprovement> bestImprovements,
        TerminationReason terminationReason) implements SolveMetrics {

    public FairnessRestrictedLocalSearchMetrics {
        if (evaluatedCandidates < 0L || acceptedCandidates < 0L || rejectedCandidates < 0L
                || changeCandidates < 0L || swapCandidates < 0L || acceptedChangeCandidates < 0L
                || acceptedSwapCandidates < 0L || scoreMismatchFailures < 0L
                || stateCorruptionFailures < 0L || fullVerificationCount < 0L
                || initialFeasibilityEvaluations < 0L
                || acceptedCandidates + rejectedCandidates != evaluatedCandidates
                || changeCandidates + swapCandidates != evaluatedCandidates
                || acceptedChangeCandidates + acceptedSwapCandidates != acceptedCandidates) {
            throw new IllegalArgumentException("fairness restricted metric count가 모순됩니다.");
        }
        Objects.requireNonNull(bestImprovements, "bestImprovements");
        Objects.requireNonNull(selectorMetrics, "selectorMetrics");
        Objects.requireNonNull(terminationReason, "terminationReason");
        bestImprovements = List.copyOf(bestImprovements);
    }

    @Override
    public String engineId() {
        return "POJO_FAIRNESS_RESTRICTED_LOCAL_MOVE";
    }

    public long rollbackAttemptCount() {
        return rejectedCandidates;
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
