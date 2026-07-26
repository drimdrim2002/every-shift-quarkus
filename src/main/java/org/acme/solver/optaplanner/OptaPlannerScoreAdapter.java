package org.acme.solver.optaplanner;

import java.util.Objects;

import org.acme.solver.core.RosterScore;
import org.optaplanner.core.api.score.buildin.bendable.BendableScore;

/**
 * OptaPlanner 점수와 엔진 중립 점수 사이의 유일한 변환 경계입니다.
 */
public final class OptaPlannerScoreAdapter {

    private OptaPlannerScoreAdapter() {
    }

    public static RosterScore toRosterScore(BendableScore score) {
        Objects.requireNonNull(score, "score");
        if (score.hardLevelsSize() != RosterScore.HARD_LEVELS
                || score.softLevelsSize() != RosterScore.SOFT_LEVELS) {
            throw new IllegalArgumentException(
                    "OptaPlanner 점수는 1 hard/4 soft 구조여야 합니다: "
                            + score.hardLevelsSize() + " hard/"
                            + score.softLevelsSize() + " soft");
        }

        return RosterScore.of(
                score.hardScore(0),
                score.softScore(0),
                score.softScore(1),
                score.softScore(2),
                score.softScore(3));
    }

    public static BendableScore toBendableScore(RosterScore score) {
        Objects.requireNonNull(score, "score");
        return BendableScore.of(
                new int[] { score.hardScore() },
                new int[] {
                        score.softScore(0),
                        score.softScore(1),
                        score.softScore(2),
                        score.softScore(3)
                });
    }
}
