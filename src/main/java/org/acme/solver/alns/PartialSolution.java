package org.acme.solver.alns;

import java.util.Arrays;

/**
 * 한 ALNS transaction 안에서만 존재하는 미배정(-1) 허용 표현입니다.
 * RosterScore, RosterSolution, callback/best API를 의도적으로 제공하지 않습니다.
 */
final class PartialSolution {

    private static final int UNASSIGNED = -1;

    private final int employeeCount;
    private final int[] originalAssignments;
    private final int[] assignments;
    private final boolean[] removed;
    private final int[] removedShiftIndexes;

    PartialSolution(int employeeCount, int[] completeAssignments, int[] removedShiftIndexes) {
        if (employeeCount < 1) {
            throw new IllegalArgumentException("partial solution에는 한 명 이상의 직원이 필요합니다.");
        }
        this.employeeCount = employeeCount;
        this.originalAssignments = Arrays.copyOf(completeAssignments, completeAssignments.length);
        this.assignments = Arrays.copyOf(completeAssignments, completeAssignments.length);
        this.removed = new boolean[completeAssignments.length];
        this.removedShiftIndexes = Arrays.copyOf(removedShiftIndexes, removedShiftIndexes.length);
        Arrays.sort(this.removedShiftIndexes);

        for (int shiftIndex = 0; shiftIndex < originalAssignments.length; shiftIndex++) {
            int employeeIndex = originalAssignments[shiftIndex];
            if (employeeIndex < 0 || employeeIndex >= employeeCount) {
                throw new IllegalArgumentException("partial baseline은 complete여야 합니다: " + shiftIndex);
            }
        }
        for (int shiftIndex : this.removedShiftIndexes) {
            if (shiftIndex < 0 || shiftIndex >= assignments.length) {
                throw new IllegalArgumentException("제거 shiftIndex 범위를 벗어났습니다: " + shiftIndex);
            }
            if (removed[shiftIndex]) {
                throw new IllegalArgumentException("중복 제거 shift입니다: " + shiftIndex);
            }
            removed[shiftIndex] = true;
        }
        reset();
    }

    void reset() {
        System.arraycopy(originalAssignments, 0, assignments, 0, assignments.length);
        for (int shiftIndex : removedShiftIndexes) {
            assignments[shiftIndex] = UNASSIGNED;
        }
    }

    int employeeCount() {
        return employeeCount;
    }

    int assignment(int shiftIndex) {
        return assignments[shiftIndex];
    }

    int originalAssignment(int shiftIndex) {
        return originalAssignments[shiftIndex];
    }

    void assign(int shiftIndex, int employeeIndex) {
        if (shiftIndex < 0 || shiftIndex >= assignments.length || !removed[shiftIndex]) {
            throw new IllegalArgumentException("destroy가 제거하지 않은 shift는 repair할 수 없습니다: " + shiftIndex);
        }
        if (assignments[shiftIndex] != UNASSIGNED) {
            throw new IllegalStateException("이미 repair된 shift입니다: " + shiftIndex);
        }
        if (employeeIndex < 0 || employeeIndex >= employeeCount) {
            throw new IllegalArgumentException("employeeIndex 범위를 벗어났습니다: " + employeeIndex);
        }
        assignments[shiftIndex] = employeeIndex;
    }

    int[] removedShiftIndexes() {
        return Arrays.copyOf(removedShiftIndexes, removedShiftIndexes.length);
    }

    int[] unassignedShiftIndexes() {
        int count = 0;
        for (int shiftIndex : removedShiftIndexes) {
            if (assignments[shiftIndex] == UNASSIGNED) {
                count++;
            }
        }
        int[] result = new int[count];
        int offset = 0;
        for (int shiftIndex : removedShiftIndexes) {
            if (assignments[shiftIndex] == UNASSIGNED) {
                result[offset++] = shiftIndex;
            }
        }
        return result;
    }

    boolean isComplete() {
        for (int shiftIndex : removedShiftIndexes) {
            if (assignments[shiftIndex] == UNASSIGNED) {
                return false;
            }
        }
        return true;
    }

    int[] completeAssignments() {
        if (!isComplete()) {
            throw new IllegalStateException("partial assignment은 complete candidate로 변환할 수 없습니다.");
        }
        return Arrays.copyOf(assignments, assignments.length);
    }
}
