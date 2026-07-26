package org.acme.solver.alns;

import java.util.Objects;

import org.acme.solver.core.RosterScore;

/** Phase 6A baseline의 엄격한 개선 전용 수락 정책입니다. */
public final class ImprovementOnlyAcceptance implements AcceptancePolicy {

    public static final String ID = "IMPROVEMENT_ONLY";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean accept(RosterScore currentScore, RosterScore candidateScore) {
        Objects.requireNonNull(currentScore, "currentScore");
        Objects.requireNonNull(candidateScore, "candidateScore");
        return candidateScore.compareTo(currentScore) > 0;
    }
}
