package org.acme.solver.move;

import java.util.List;

import org.acme.solver.core.PlanningProblem;

/** 두 mutable shift의 담당 직원을 교환합니다. */
public final class SwapMove implements Move {

    private final List<AssignmentChange> changes;

    private SwapMove(List<AssignmentChange> changes) {
        this.changes = changes;
    }

    public static SwapMove create(
            PlanningProblem problem, SearchState state, int firstShiftIndex, int secondShiftIndex) {
        MoveSupport.validateProblem(problem, state);
        if (firstShiftIndex == secondShiftIndex) {
            throw new IllegalArgumentException("같은 shift끼리는 swap할 수 없습니다.");
        }
        int firstEmployee = state.employeeIndex(firstShiftIndex);
        int secondEmployee = state.employeeIndex(secondShiftIndex);
        if (firstEmployee == secondEmployee) {
            throw new IllegalArgumentException("동일 직원 사이의 no-op swap은 생성할 수 없습니다.");
        }
        List<AssignmentChange> changes = List.of(
                new AssignmentChange(firstShiftIndex, firstEmployee, secondEmployee),
                new AssignmentChange(secondShiftIndex, secondEmployee, firstEmployee));
        return new SwapMove(MoveSupport.validateChanges(problem, changes));
    }

    @Override
    public String moveType() {
        return "SWAP";
    }

    @Override
    public List<AssignmentChange> changes() {
        return changes;
    }

    @Override
    public void apply(SearchState state) {
        MoveSupport.apply(state, changes);
    }

    @Override
    public void undo(SearchState state) {
        MoveSupport.undo(state, changes);
    }
}
