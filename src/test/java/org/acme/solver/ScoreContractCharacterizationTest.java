package org.acme.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.acme.solver.core.RosterScore;
import org.junit.jupiter.api.Test;

class ScoreContractCharacterizationTest {

    @Test
    void employeeScheduleUsesOneHardAndFourSoftLevels() {
        assertEquals(1, RosterScore.HARD_LEVELS);
        assertEquals(4, RosterScore.SOFT_LEVELS);
    }

    @Test
    void scoreComparisonIsLexicographicInCurrentBusinessPriorityOrder() {
        RosterScore feasibleWorstSoft = score(0, -10_000, -10_000, -10_000, -10_000);
        RosterScore infeasibleBestSoft = score(-1, 10_000, 10_000, 10_000, 10_000);
        assertTrue(feasibleWorstSoft.compareTo(infeasibleBestSoft) > 0, "hard[0]이 모든 soft 레벨보다 우선해야 한다");

        assertHigherPriorityWins(0, "soft[0] 기피일 배정");
        assertHigherPriorityWins(1, "soft[1] 통합 형평성");
        assertHigherPriorityWins(2, "soft[2] 희망일 배정");

        RosterScore betterDesired = score(0, 0, 0, 1, 0);
        RosterScore worseDesired = score(0, 0, 0, 0, 0);
        assertTrue(betterDesired.compareTo(worseDesired) > 0, "soft[2] 희망일 배정은 값이 클수록 우수해야 한다");
    }

    @Test
    void feasibilityMeansHardScoreIsAtLeastZero() {
        assertTrue(score(0, -1, -1, -1, -1).isFeasible());
        assertFalse(score(-1, 1, 1, 1, 1).isFeasible());
    }

    private static void assertHigherPriorityWins(int higherSoftIndex, String description) {
        int[] better = { 0, 0, 0, 0 };
        int[] worse = { 0, 0, 0, 0 };
        better[higherSoftIndex] = 1;
        for (int index = higherSoftIndex + 1; index < better.length; index++) {
            better[index] = -10_000;
            worse[index] = 10_000;
        }

        RosterScore higherPriorityBetter = new RosterScore(0, better);
        RosterScore lowerPrioritiesBetter = new RosterScore(0, worse);
        assertTrue(higherPriorityBetter.compareTo(lowerPrioritiesBetter) > 0, description);
    }

    private static RosterScore score(int hard, int soft0, int soft1, int soft2, int soft3) {
        return RosterScore.of(hard, soft0, soft1, soft2, soft3);
    }
}
