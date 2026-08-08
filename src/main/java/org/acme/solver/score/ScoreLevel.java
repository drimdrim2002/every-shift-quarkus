package org.acme.solver.score;

/**
 * 1 hard + 4 soft 점수 벡터의 위치입니다.
 *
 * <p>soft 의미: SOFT_0=undesired, SOFT_1=fairness, SOFT_2=desired, SOFT_3=예약(미사용).
 * Night→Day 32h(NOD)는 {@link ScoreLevel#HARD}입니다.</p>
 */
public enum ScoreLevel {
    HARD(-1),
    SOFT_0(0),
    SOFT_1(1),
    SOFT_2(2),
    SOFT_3(3);

    private final int softIndex;

    ScoreLevel(int softIndex) {
        this.softIndex = softIndex;
    }

    public boolean isHard() {
        return this == HARD;
    }

    public int softIndex() {
        if (isHard()) {
            throw new IllegalStateException("hard level에는 soft index가 없습니다.");
        }
        return softIndex;
    }
}
