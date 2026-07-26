package org.acme.solver.lahc;

/** test-only fairness selector가 후보를 줄이고 소비한 과정을 기록합니다. */
public record FairnessSelectorMetrics(
        long rankingBuildCount,
        long rawReassignCandidates,
        long eligibilityCandidates,
        long fairnessHintCandidates,
        long emittedCandidates) {

    public FairnessSelectorMetrics {
        if (rankingBuildCount < 0L || rawReassignCandidates < 0L || eligibilityCandidates < 0L
                || fairnessHintCandidates < 0L || emittedCandidates < 0L
                || eligibilityCandidates > rawReassignCandidates
                || fairnessHintCandidates > eligibilityCandidates
                || emittedCandidates > fairnessHintCandidates) {
            throw new IllegalArgumentException("fairness selector metric count가 모순됩니다.");
        }
    }

    public static FairnessSelectorMetrics empty() {
        return new FairnessSelectorMetrics(0L, 0L, 0L, 0L, 0L);
    }
}
