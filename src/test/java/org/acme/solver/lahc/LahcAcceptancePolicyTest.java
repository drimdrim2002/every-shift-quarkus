package org.acme.solver.lahc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.acme.solver.core.RosterScore;
import org.junit.jupiter.api.Test;

class LahcAcceptancePolicyTest {

    @Test
    void 동점_plateau와_history_이상인_악화_후보를_수락한다() {
        RosterScore zero = score(0);
        LahcAcceptancePolicy policy = new LahcAcceptancePolicy(2, zero);

        var plateau = policy.consider(zero, zero);
        assertTrue(plateau.accepted());
        assertEquals(zero, plateau.currentAfterDecision());

        RosterScore current = score(10);
        RosterScore worseningButLateEnough = score(0);
        var worsening = policy.consider(current, worseningButLateEnough);
        assertTrue(worsening.accepted());
        assertEquals(worseningButLateEnough, worsening.currentAfterDecision());
    }

    @Test
    void current와_history보다_나쁜_후보는_거절하고_current를_history에_기록한다() {
        LahcAcceptancePolicy policy = new LahcAcceptancePolicy(1, score(0));

        var decision = policy.consider(score(5), score(-1));

        assertFalse(decision.accepted());
        assertEquals(score(5), decision.currentAfterDecision());
        assertEquals(score(5), decision.historyAfter());
        assertEquals(score(5), policy.historyScore(0));
    }

    @Test
    void history는_wrap_around하고_기존_slot보다_좋을_때만_갱신한다() {
        LahcAcceptancePolicy policy = new LahcAcceptancePolicy(2, score(0));

        var first = policy.consider(score(0), score(1));
        var second = policy.consider(score(1), score(2));
        var wrapped = policy.consider(score(2), score(1));

        assertEquals(0, first.historySlot());
        assertEquals(1, second.historySlot());
        assertEquals(0, wrapped.historySlot());
        assertTrue(wrapped.accepted());
        assertEquals(score(1), wrapped.historyBefore());
        assertEquals(score(1), wrapped.historyAfter());
        assertEquals(3, policy.evaluationCount());
    }

    private static RosterScore score(int soft0) {
        return RosterScore.of(0, soft0, 0, 0, 0);
    }
}
