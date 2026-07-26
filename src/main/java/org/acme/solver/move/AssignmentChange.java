package org.acme.solver.move;

/**
 * 하나의 shift assignment 역연산 계약입니다.
 */
public record AssignmentChange(int shiftIndex, int oldEmployeeIndex, int newEmployeeIndex) {

    public AssignmentChange {
        if (shiftIndex < 0) {
            throw new IllegalArgumentException("shiftIndex는 음수일 수 없습니다: " + shiftIndex);
        }
        if (oldEmployeeIndex < 0 || newEmployeeIndex < 0) {
            throw new IllegalArgumentException("move는 complete assignment만 다룰 수 있습니다.");
        }
        if (oldEmployeeIndex == newEmployeeIndex) {
            throw new IllegalArgumentException("no-op assignment change는 만들 수 없습니다.");
        }
    }

    AssignmentChange inverse() {
        return new AssignmentChange(shiftIndex, newEmployeeIndex, oldEmployeeIndex);
    }
}
