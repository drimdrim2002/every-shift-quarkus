package org.acme.solver.score;

import java.util.Objects;

import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.TerminationReason;

/** full/incremental 불일치 시 탐색을 중단하고 마지막 verified best만 전달합니다. */
public final class ScoreMismatchException extends IllegalStateException {

    private final RosterScore incrementalScore;
    private final RosterScore fullScore;
    private final RosterSolution lastVerifiedBest;

    public ScoreMismatchException(
            String message,
            RosterScore incrementalScore,
            RosterScore fullScore,
            RosterSolution lastVerifiedBest) {
        super(message);
        this.incrementalScore = Objects.requireNonNull(incrementalScore, "incrementalScore");
        this.fullScore = Objects.requireNonNull(fullScore, "fullScore");
        this.lastVerifiedBest = lastVerifiedBest;
    }

    public TerminationReason terminationReason() {
        return TerminationReason.SCORE_MISMATCH;
    }

    public RosterScore incrementalScore() {
        return incrementalScore;
    }

    public RosterScore fullScore() {
        return fullScore;
    }

    public RosterSolution lastVerifiedBest() {
        return lastVerifiedBest;
    }
}
