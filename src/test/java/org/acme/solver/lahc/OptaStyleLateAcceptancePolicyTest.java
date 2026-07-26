package org.acme.solver.lahc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.acme.solver.core.RosterScore;
import org.junit.jupiter.api.Test;

class OptaStyleLateAcceptancePolicyTest {

    @Test
    void current와_400_step_전_history_중_하나보다_나쁘지_않으면_수락한다() {
        OptaStyleLateAcceptancePolicy policy = new OptaStyleLateAcceptancePolicy(2, score(0));

        OptaStyleLateAcceptancePolicy.Decision first = policy.evaluate(score(0), score(10));
        assertTrue(first.accepted());
        policy.completeAcceptedStep(first, score(10));

        OptaStyleLateAcceptancePolicy.Decision second = policy.evaluate(score(10), score(0));
        assertTrue(second.accepted(), "현재보다 나빠도 slot 1의 초기 history와 동점이면 수락한다.");
        policy.completeAcceptedStep(second, score(0));

        OptaStyleLateAcceptancePolicy.Decision third = policy.evaluate(score(0), score(5));
        assertTrue(third.accepted(), "slot 0의 10보다 낮아도 현재 0보다 좋으면 수락한다.");
    }

    @Test
    void 수락_step은_history_slot을_최고값으로_보존하지_않고_현재_score로_덮어쓴다() {
        OptaStyleLateAcceptancePolicy policy = new OptaStyleLateAcceptancePolicy(2, score(0));

        OptaStyleLateAcceptancePolicy.Decision first = policy.evaluate(score(0), score(10));
        policy.completeAcceptedStep(first, score(10));
        OptaStyleLateAcceptancePolicy.Decision second = policy.evaluate(score(10), score(0));
        policy.completeAcceptedStep(second, score(0));
        OptaStyleLateAcceptancePolicy.Decision third = policy.evaluate(score(0), score(5));
        policy.completeAcceptedStep(third, score(5));

        assertEquals(score(5), policy.historyScore(0));
        assertEquals(score(0), policy.historyScore(1));
        assertEquals(3L, policy.completedStepCount());
    }

    @Test
    void 거절된_후보는_history_step을_전진시키지_않는다() {
        OptaStyleLateAcceptancePolicy policy = new OptaStyleLateAcceptancePolicy(2, score(0));

        OptaStyleLateAcceptancePolicy.Decision rejected = policy.evaluate(score(0), score(-1));

        assertFalse(rejected.accepted());
        assertEquals(0L, policy.completedStepCount());
        assertEquals(0, policy.currentHistorySlot());
        assertEquals(score(0), policy.historyScore(0));
    }

    private static RosterScore score(int soft0) {
        return RosterScore.of(0, soft0, 0, 0, 0);
    }
}
