package org.acme.solver.lahc;

import java.util.List;
import java.util.Objects;

import org.acme.solver.core.RosterScore;
import org.acme.solver.core.SolveMetrics;
import org.acme.solver.core.TerminationReason;

/** test-only prefix reassign intensification의 full-score 관측값입니다. */
public record ExhaustivePrefixReassignIntensificationMetrics(
        long generatedCandidates,
        long evaluatedCandidates,
        long acceptedCandidates,
        long rejectedCandidates,
        long fullVerificationCount,
        long scoreMismatchFailures,
        long stateCorruptionFailures,
        int candidateLimitPerPass,
        PreceptorPrefixGuidedProtectedReassignSelector.Metrics selectorMetrics,
        List<BestImprovement> bestImprovements,
        TerminationReason terminationReason) implements SolveMetrics {

    public ExhaustivePrefixReassignIntensificationMetrics {
        if (generatedCandidates < 0L || evaluatedCandidates < 0L || acceptedCandidates < 0L
                || rejectedCandidates < 0L || fullVerificationCount < 0L || scoreMismatchFailures < 0L
                || stateCorruptionFailures < 0L || candidateLimitPerPass < 1) {
            throw new IllegalArgumentException("prefix reassign metric count가 올바르지 않습니다.");
        }
        if (acceptedCandidates + rejectedCandidates != evaluatedCandidates) {
            throw new IllegalArgumentException("prefix reassign 수락/거절 집계가 후보 평가 수와 다릅니다.");
        }
        Objects.requireNonNull(selectorMetrics, "selectorMetrics");
        bestImprovements = List.copyOf(Objects.requireNonNull(bestImprovements, "bestImprovements"));
        Objects.requireNonNull(terminationReason, "terminationReason");
    }

    @Override
    public String engineId() {
        return "POJO_EXHAUSTIVE_PREFIX_REASSIGN_INTENSIFICATION_TEST_ONLY";
    }

    public long rollbackAttemptCount() {
        return rejectedCandidates;
    }

    public long evaluationCount() {
        return evaluatedCandidates;
    }

    public long rollbackFailureCount() {
        return stateCorruptionFailures;
    }

    public record BestImprovement(long evaluation, long elapsedMillis, RosterScore score) {
        public BestImprovement {
            if (evaluation < 0L || elapsedMillis < 0L) {
                throw new IllegalArgumentException("best improvement 시점은 음수일 수 없습니다.");
            }
            Objects.requireNonNull(score, "score");
        }
    }
}
