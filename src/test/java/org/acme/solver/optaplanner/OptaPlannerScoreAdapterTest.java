package org.acme.solver.optaplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;

import org.acme.solver.core.RosterScore;
import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.score.buildin.bendable.BendableScore;

class OptaPlannerScoreAdapterTest {

    @Test
    void convertsBothDirectionsWithoutChangingValues() {
        BendableScore engineScore = BendableScore.of(
                new int[] { 0 },
                new int[] { -30, -120, -5409, 240 });

        RosterScore rosterScore = OptaPlannerScoreAdapter.toRosterScore(engineScore);

        assertEquals(RosterScore.of(0, -30, -120, -5409, 240), rosterScore);
        assertEquals(engineScore, OptaPlannerScoreAdapter.toBendableScore(rosterScore));
    }

    @Test
    void rejectsAnyEngineScoreWithDifferentLevelCount() {
        assertThrows(IllegalArgumentException.class, () -> OptaPlannerScoreAdapter.toRosterScore(
                BendableScore.of(new int[] { 0 }, new int[] { 1, 2, 3 })));
        assertThrows(IllegalArgumentException.class, () -> OptaPlannerScoreAdapter.toRosterScore(
                BendableScore.of(new int[] { 0, 0 }, new int[] { 1, 2, 3, 4 })));
    }

    @Test
    void rosterAndOptaPlannerComparisonStayEquivalentForSeededScores() {
        Random random = new Random(42);
        for (int sample = 0; sample < 10_000; sample++) {
            BendableScore left = randomScore(random);
            BendableScore right = randomScore(random);

            int engineComparison = Integer.signum(left.compareTo(right));
            int rosterComparison = Integer.signum(
                    OptaPlannerScoreAdapter.toRosterScore(left)
                            .compareTo(OptaPlannerScoreAdapter.toRosterScore(right)));

            assertEquals(engineComparison, rosterComparison, "sample=" + sample);
        }
    }

    private static BendableScore randomScore(Random random) {
        return BendableScore.of(
                new int[] { random.nextInt() },
                new int[] {
                        random.nextInt(),
                        random.nextInt(),
                        random.nextInt(),
                        random.nextInt()
                });
    }
}
