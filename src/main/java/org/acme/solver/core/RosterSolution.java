package org.acme.solver.core;

import java.util.Arrays;
import java.util.Objects;

/**
 * 모든 shift가 직원에게 배정된 complete solution의 불변 snapshot입니다.
 */
public final class RosterSolution {

    private final int employeeCount;
    private final int[] employeeIndexByShift;
    private final RosterScore score;

    public RosterSolution(int employeeCount, int[] employeeIndexByShift, RosterScore score) {
        if (employeeCount < 0) {
            throw new IllegalArgumentException("employeeCount는 음수일 수 없습니다: " + employeeCount);
        }
        Objects.requireNonNull(employeeIndexByShift, "employeeIndexByShift");
        this.score = Objects.requireNonNull(score, "score");
        this.employeeCount = employeeCount;
        this.employeeIndexByShift = Arrays.copyOf(employeeIndexByShift, employeeIndexByShift.length);

        for (int shiftIndex = 0; shiftIndex < this.employeeIndexByShift.length; shiftIndex++) {
            int employeeIndex = this.employeeIndexByShift[shiftIndex];
            if (employeeIndex < 0 || employeeIndex >= employeeCount) {
                throw new IllegalArgumentException(
                        "RosterSolution은 모든 shift가 유효한 직원에게 배정되어야 합니다: shiftIndex="
                                + shiftIndex + ", employeeIndex=" + employeeIndex);
            }
        }
    }

    public int employeeCount() {
        return employeeCount;
    }

    public int shiftCount() {
        return employeeIndexByShift.length;
    }

    public int employeeIndex(int shiftIndex) {
        return employeeIndexByShift[shiftIndex];
    }

    public int[] employeeIndexByShift() {
        return Arrays.copyOf(employeeIndexByShift, employeeIndexByShift.length);
    }

    public RosterScore score() {
        return score;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RosterSolution that)) {
            return false;
        }
        return employeeCount == that.employeeCount
                && score.equals(that.score)
                && Arrays.equals(employeeIndexByShift, that.employeeIndexByShift);
    }

    @Override
    public int hashCode() {
        int result = 31 * Integer.hashCode(employeeCount) + score.hashCode();
        return 31 * result + Arrays.hashCode(employeeIndexByShift);
    }
}
