package org.acme.solver.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * 솔버 구현과 무관한 로스터 점수 값 객체입니다.
 *
 * <p>점수는 1개의 hard 레벨과 4개의 soft 레벨로 고정되며, 앞 레벨이
 * 뒤 레벨보다 항상 우선하는 사전식 순서로 비교합니다.</p>
 *
 * <p>soft 의미(현재): soft[0]=undesired, soft[1]=fairness, soft[2]=desired, soft[3]=예약.
 * Night→Day 32h(NOD) 위반은 hard에 포함됩니다.</p>
 */
@RegisterForReflection
public final class RosterScore implements Comparable<RosterScore> {

    public static final int HARD_LEVELS = 1;
    public static final int SOFT_LEVELS = 4;

    private final int hardScore;
    private final int[] softScores;

    /**
     * 정수 점수로 값 객체를 만듭니다. 전달받은 배열은 즉시 복사합니다.
     */
    public RosterScore(int hardScore, int[] softScores) {
        this.hardScore = hardScore;
        this.softScores = validateAndCopy(softScores);
    }

    public static RosterScore of(int hardScore, int... softScores) {
        return new RosterScore(hardScore, softScores);
    }

    /**
     * JSON과 외부 숫자 입력을 위한 overflow 검증 생성자입니다.
     */
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public static RosterScore fromJson(
            @JsonProperty(value = "hardScore", required = true) Long hardScore,
            @JsonProperty(value = "softScores", required = true) List<Long> softScores) {
        if (hardScore == null) {
            throw new IllegalArgumentException("hardScore는 필수입니다.");
        }
        if (softScores == null) {
            throw new IllegalArgumentException("softScores는 필수입니다.");
        }
        if (softScores.size() != SOFT_LEVELS) {
            throw new IllegalArgumentException(
                    "soft score 레벨 수는 " + SOFT_LEVELS + "개여야 합니다: " + softScores.size());
        }

        int[] validatedSoftScores = new int[SOFT_LEVELS];
        for (int index = 0; index < SOFT_LEVELS; index++) {
            Long value = softScores.get(index);
            if (value == null) {
                throw new IllegalArgumentException("softScores[" + index + "]는 null일 수 없습니다.");
            }
            validatedSoftScores[index] = toIntExact(value, "softScores[" + index + "]");
        }
        return new RosterScore(toIntExact(hardScore, "hardScore"), validatedSoftScores);
    }

    @JsonProperty("hardScore")
    public int hardScore() {
        return hardScore;
    }

    /**
     * 불변 리스트 snapshot을 반환하며 내부 배열을 노출하지 않습니다.
     */
    @JsonProperty("softScores")
    public List<Integer> softScores() {
        List<Integer> snapshot = new ArrayList<>(SOFT_LEVELS);
        for (int softScore : softScores) {
            snapshot.add(softScore);
        }
        return Collections.unmodifiableList(snapshot);
    }

    public int softScore(int index) {
        if (index < 0 || index >= SOFT_LEVELS) {
            throw new IndexOutOfBoundsException("soft score index 범위는 0.." + (SOFT_LEVELS - 1) + "입니다: " + index);
        }
        return softScores[index];
    }

    @JsonIgnore
    public boolean isFeasible() {
        return hardScore >= 0;
    }

    /**
     * 기준 점수와의 hard delta를 long으로 계산해 int overflow를 방지합니다.
     */
    public long hardDeltaFrom(RosterScore baseline) {
        Objects.requireNonNull(baseline, "baseline");
        return (long) hardScore - (long) baseline.hardScore;
    }

    /**
     * 기준 점수와의 soft delta를 long으로 계산해 int overflow를 방지합니다.
     */
    public long softDeltaFrom(RosterScore baseline, int index) {
        Objects.requireNonNull(baseline, "baseline");
        if (index < 0 || index >= SOFT_LEVELS) {
            throw new IndexOutOfBoundsException("soft score index 범위는 0.." + (SOFT_LEVELS - 1) + "입니다: " + index);
        }
        return (long) softScores[index] - (long) baseline.softScores[index];
    }

    @Override
    public int compareTo(RosterScore other) {
        Objects.requireNonNull(other, "other");

        int hardComparison = Integer.compare(hardScore, other.hardScore);
        if (hardComparison != 0) {
            return hardComparison;
        }
        for (int index = 0; index < SOFT_LEVELS; index++) {
            int softComparison = Integer.compare(softScores[index], other.softScores[index]);
            if (softComparison != 0) {
                return softComparison;
            }
        }
        return 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RosterScore that)) {
            return false;
        }
        return hardScore == that.hardScore && Arrays.equals(softScores, that.softScores);
    }

    @Override
    public int hashCode() {
        return 31 * Integer.hashCode(hardScore) + Arrays.hashCode(softScores);
    }

    /**
     * 외부 계약에 노출된 기존 점수 벡터 문자열 형식을 유지합니다.
     */
    @Override
    public String toString() {
        return "[" + hardScore + "]hard/["
                + softScores[0] + "/"
                + softScores[1] + "/"
                + softScores[2] + "/"
                + softScores[3] + "]soft";
    }

    private static int[] validateAndCopy(int[] softScores) {
        Objects.requireNonNull(softScores, "softScores");
        if (softScores.length != SOFT_LEVELS) {
            throw new IllegalArgumentException(
                    "soft score 레벨 수는 " + SOFT_LEVELS + "개여야 합니다: " + softScores.length);
        }
        return Arrays.copyOf(softScores, softScores.length);
    }

    private static int toIntExact(long value, String fieldName) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(fieldName + " 값이 int 범위를 벗어났습니다: " + value);
        }
        return (int) value;
    }
}
