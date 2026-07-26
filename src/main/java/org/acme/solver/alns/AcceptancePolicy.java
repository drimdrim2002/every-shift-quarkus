package org.acme.solver.alns;

import org.acme.solver.core.RosterScore;

/** full/incremental 검증이 끝난 complete candidate만 받는 수락 정책 SPI입니다. */
public interface AcceptancePolicy {

    String id();

    boolean accept(RosterScore currentScore, RosterScore candidateScore);
}
