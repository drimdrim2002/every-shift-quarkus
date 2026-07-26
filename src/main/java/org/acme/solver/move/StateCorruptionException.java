package org.acme.solver.move;

import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.TerminationReason;

/** rollback 뒤 fingerprint/score/cache/immutable index가 복구되지 않은 치명적 상태입니다. */
public final class StateCorruptionException extends IllegalStateException {

    private final RosterSolution lastVerifiedBest;

    public StateCorruptionException(String message, Throwable cause, RosterSolution lastVerifiedBest) {
        super(message, cause);
        this.lastVerifiedBest = lastVerifiedBest;
    }

    public TerminationReason terminationReason() {
        return TerminationReason.STATE_CORRUPTION;
    }

    public RosterSolution lastVerifiedBest() {
        return lastVerifiedBest;
    }
}
