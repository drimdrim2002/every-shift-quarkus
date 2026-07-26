package org.acme.solver.move;

import java.util.List;

import org.acme.solver.core.PlanningProblem;

/** 단일 mutable shift를 다른 직원에게 재배정합니다. */
public final class ReassignMove implements Move {

    private final List<AssignmentChange> changes;

    private ReassignMove(List<AssignmentChange> changes) {
        this.changes = changes;
    }

    public static ReassignMove create(
            PlanningProblem problem, SearchState state, int shiftIndex, int newEmployeeIndex) {
        MoveSupport.validateProblem(problem, state);
        AssignmentChange change = new AssignmentChange(
                shiftIndex, state.employeeIndex(shiftIndex), newEmployeeIndex);
        return new ReassignMove(MoveSupport.validateChanges(problem, List.of(change)));
    }

    @Override
    public String moveType() {
        return "REASSIGN";
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
