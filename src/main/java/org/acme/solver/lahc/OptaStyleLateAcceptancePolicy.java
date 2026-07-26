package org.acme.solver.lahc;

import java.util.Arrays;
import java.util.Objects;

import org.acme.solver.core.RosterScore;

/**
 * OptaPlanner 10.0.0 기본 Late Acceptance의 step 단위 규칙입니다.
 *
 * <p>후보 평가는 여러 번 일어날 수 있지만, history는 수락된 step이 끝날 때만 한 칸 전진합니다.
 * 수락 score는 기존 history score보다 낮더라도 해당 slot에 그대로 기록합니다.</p>
 */
public final class OptaStyleLateAcceptancePolicy {

    public record Decision(
            long stepIndex,
            int historySlot,
            boolean accepted,
            RosterScore currentScore,
            RosterScore candidateScore,
            RosterScore historyScore) {

        public Decision {
            if (stepIndex < 0L) {
                throw new IllegalArgumentException("stepIndex는 음수일 수 없습니다.");
            }
            if (historySlot < 0) {
                throw new IllegalArgumentException("historySlot은 음수일 수 없습니다.");
            }
            Objects.requireNonNull(currentScore, "currentScore");
            Objects.requireNonNull(candidateScore, "candidateScore");
            Objects.requireNonNull(historyScore, "historyScore");
        }
    }

    private final RosterScore[] history;
    private long completedStepCount;

    public OptaStyleLateAcceptancePolicy(int historyLength, RosterScore initialScore) {
        if (historyLength <= 0) {
            throw new IllegalArgumentException("Late Acceptance history length는 양수여야 합니다: " + historyLength);
        }
        Objects.requireNonNull(initialScore, "initialScore");
        this.history = new RosterScore[historyLength];
        Arrays.fill(history, initialScore);
    }

    /** 현재 history slot과 현재 score 중 하나 이상보다 나쁘지 않을 때만 수락합니다. */
    public Decision evaluate(RosterScore currentScore, RosterScore candidateScore) {
        Objects.requireNonNull(currentScore, "currentScore");
        Objects.requireNonNull(candidateScore, "candidateScore");
        int slot = currentHistorySlot();
        RosterScore historyScore = history[slot];
        boolean accepted = candidateScore.compareTo(historyScore) >= 0
                || candidateScore.compareTo(currentScore) >= 0;
        return new Decision(
                completedStepCount,
                slot,
                accepted,
                currentScore,
                candidateScore,
                historyScore);
    }

    /** 수락된 후보가 commit된 뒤에만 호출합니다. */
    public void completeAcceptedStep(Decision decision, RosterScore acceptedScore) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(acceptedScore, "acceptedScore");
        if (!decision.accepted()) {
            throw new IllegalArgumentException("거절된 후보는 Late Acceptance step을 완료할 수 없습니다.");
        }
        if (decision.stepIndex() != completedStepCount
                || decision.historySlot() != currentHistorySlot()) {
            throw new IllegalStateException("Late Acceptance decision이 현재 step과 일치하지 않습니다.");
        }
        if (!decision.candidateScore().equals(acceptedScore)) {
            throw new IllegalArgumentException("commit score가 평가한 candidate score와 다릅니다.");
        }
        history[decision.historySlot()] = acceptedScore;
        completedStepCount++;
    }

    public long completedStepCount() {
        return completedStepCount;
    }

    public int historyLength() {
        return history.length;
    }

    public int currentHistorySlot() {
        return (int) Math.floorMod(completedStepCount, history.length);
    }

    public RosterScore historyScore(int slot) {
        if (slot < 0 || slot >= history.length) {
            throw new IndexOutOfBoundsException("history slot 범위는 0.." + (history.length - 1) + "입니다: " + slot);
        }
        return history[slot];
    }

    public RosterScore[] historySnapshot() {
        return Arrays.copyOf(history, history.length);
    }
}
