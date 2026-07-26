package org.acme.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;

import org.acme.model.EmployeeSchedule;
import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.domain.solution.PlanningScore;
import org.optaplanner.core.api.score.buildin.bendable.BendableScore;

class ScoreContractCharacterizationTest {

    @Test
    void employeeScheduleUsesOneHardAndFourSoftLevels() throws Exception {
        Field scoreField = EmployeeSchedule.class.getDeclaredField("score");
        PlanningScore planningScore = scoreField.getAnnotation(PlanningScore.class);

        assertEquals(1, planningScore.bendableHardLevelsSize());
        assertEquals(4, planningScore.bendableSoftLevelsSize());
    }

    @Test
    void scoreComparisonIsLexicographicInCurrentBusinessPriorityOrder() {
        BendableScore feasibleWorstSoft = score(0, -10_000, -10_000, -10_000, -10_000);
        BendableScore infeasibleBestSoft = score(-1, 10_000, 10_000, 10_000, 10_000);
        assertTrue(feasibleWorstSoft.compareTo(infeasibleBestSoft) > 0, "hard[0]이 모든 soft 레벨보다 우선해야 한다");

        assertHigherPriorityWins(0, "soft[0] 야간 후 32시간 휴식");
        assertHigherPriorityWins(1, "soft[1] 기피일 배정");
        assertHigherPriorityWins(2, "soft[2] 통합 형평성");

        BendableScore betterDesired = score(0, 0, 0, 0, 1);
        BendableScore worseDesired = score(0, 0, 0, 0, 0);
        assertTrue(betterDesired.compareTo(worseDesired) > 0, "soft[3] 희망일 배정은 값이 클수록 우수해야 한다");
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

        BendableScore higherPriorityBetter = BendableScore.of(new int[] { 0 }, better);
        BendableScore lowerPrioritiesBetter = BendableScore.of(new int[] { 0 }, worse);
        assertTrue(higherPriorityBetter.compareTo(lowerPrioritiesBetter) > 0, description);
    }

    private static BendableScore score(int hard, int soft0, int soft1, int soft2, int soft3) {
        return BendableScore.of(new int[] { hard }, new int[] { soft0, soft1, soft2, soft3 });
    }
}
