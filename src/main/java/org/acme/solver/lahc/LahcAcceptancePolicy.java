package org.acme.solver.lahc;

import java.util.Arrays;
import java.util.Objects;

import org.acme.solver.core.RosterScore;

/** 계획 문서 4.6의 최대화 LAHC 수락/history 규칙을 그대로 캡슐화합니다. */
public final class LahcAcceptancePolicy {

    public record Decision(
            long evaluationIndex,
            int historySlot,
            boolean accepted,
            RosterScore currentAfterDecision,
            RosterScore historyBefore,
            RosterScore historyAfter) {
    }

    private final RosterScore[] history;
    private long evaluationCount;

    public LahcAcceptancePolicy(int historyLength, RosterScore initialScore) {
        if (historyLength <= 0) {
            throw new IllegalArgumentException("LAHC history length는 양수여야 합니다: " + historyLength);
        }
        Objects.requireNonNull(initialScore, "initialScore");
        this.history = new RosterScore[historyLength];
        Arrays.fill(this.history, initialScore);
    }

    /** no-op이 아닌 complete candidate에 대해서만 호출해야 합니다. */
    public Decision consider(RosterScore currentScore, RosterScore candidateScore) {
        Objects.requireNonNull(currentScore, "currentScore");
        Objects.requireNonNull(candidateScore, "candidateScore");
        long evaluationIndex = evaluationCount;
        int slot = (int) Math.floorMod(evaluationIndex, history.length);
        RosterScore historyBefore = history[slot];
        boolean accepted = candidateScore.compareTo(currentScore) >= 0
                || candidateScore.compareTo(historyBefore) >= 0;
        RosterScore currentAfterDecision = accepted ? candidateScore : currentScore;
        if (currentAfterDecision.compareTo(historyBefore) > 0) {
            history[slot] = currentAfterDecision;
        }
        evaluationCount++;
        return new Decision(
                evaluationIndex,
                slot,
                accepted,
                currentAfterDecision,
                historyBefore,
                history[slot]);
    }

    public long evaluationCount() {
        return evaluationCount;
    }

    public int historyLength() {
        return history.length;
    }

    public RosterScore historyScore(int slot) {
        return history[slot];
    }

    public RosterScore[] historySnapshot() {
        return Arrays.copyOf(history, history.length);
    }
}
