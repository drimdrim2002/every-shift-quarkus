package org.acme.solver.move;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.acme.solver.core.PlanningProblem;

final class MoveSupport {

    private MoveSupport() {
    }

    static void validateProblem(PlanningProblem problem, SearchState state) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(state, "state");
        if (state.problem() != problem) {
            throw new IllegalArgumentException("move와 SearchState는 같은 PlanningProblem을 사용해야 합니다.");
        }
    }

    static List<AssignmentChange> validateChanges(
            PlanningProblem problem, List<AssignmentChange> changes) {
        List<AssignmentChange> copy = List.copyOf(Objects.requireNonNull(changes, "changes"));
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("move에는 하나 이상의 assignment change가 필요합니다.");
        }
        Set<Integer> seenShifts = new HashSet<>();
        for (AssignmentChange change : copy) {
            if (change.shiftIndex() >= problem.shiftCount()) {
                throw new IllegalArgumentException("shiftIndex 범위를 벗어났습니다: " + change.shiftIndex());
            }
            if (change.oldEmployeeIndex() >= problem.employeeCount()
                    || change.newEmployeeIndex() >= problem.employeeCount()) {
                throw new IllegalArgumentException("employeeIndex 범위를 벗어났습니다: " + change);
            }
            if (!problem.isMutableShift(change.shiftIndex())) {
                throw new IllegalArgumentException(
                        "pinned/immutable shift move 생성은 차단됩니다: " + change.shiftIndex());
            }
            if (!seenShifts.add(change.shiftIndex())) {
                throw new IllegalArgumentException("한 move에서 shift를 중복 변경할 수 없습니다: " + change.shiftIndex());
            }
        }
        return copy;
    }

    static void apply(SearchState state, List<AssignmentChange> changes) {
        validateExpected(state, changes, false);
        for (AssignmentChange change : changes) {
            state.changeAssignment(
                    change.shiftIndex(), change.oldEmployeeIndex(), change.newEmployeeIndex());
        }
    }

    static void undo(SearchState state, List<AssignmentChange> changes) {
        validateExpected(state, changes, true);
        for (int index = changes.size() - 1; index >= 0; index--) {
            AssignmentChange change = changes.get(index);
            state.changeAssignment(
                    change.shiftIndex(), change.newEmployeeIndex(), change.oldEmployeeIndex());
        }
    }

    static void validateExpected(SearchState state, List<AssignmentChange> changes, boolean undo) {
        for (AssignmentChange change : changes) {
            int expected = undo ? change.newEmployeeIndex() : change.oldEmployeeIndex();
            int actual = state.employeeIndex(change.shiftIndex());
            if (actual != expected) {
                throw new IllegalStateException(
                        "stale " + (undo ? "undo" : "move") + "입니다: shiftIndex="
                                + change.shiftIndex() + ", expected=" + expected + ", actual=" + actual);
            }
        }
    }
}
