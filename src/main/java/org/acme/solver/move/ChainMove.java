package org.acme.solver.move;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.acme.solver.core.PlanningProblem;

/** 여러 shift 재배정을 하나의 원자적 연쇄 move로 적용합니다. */
public final class ChainMove implements Move {

    public record Leg(int shiftIndex, int newEmployeeIndex) {
    }

    private final List<AssignmentChange> changes;

    private ChainMove(List<AssignmentChange> changes) {
        this.changes = changes;
    }

    public static ChainMove create(
            PlanningProblem problem, SearchState state, List<Leg> legs) {
        MoveSupport.validateProblem(problem, state);
        List<Leg> legCopy = List.copyOf(Objects.requireNonNull(legs, "legs"));
        if (legCopy.size() < 2) {
            throw new IllegalArgumentException("ChainMove에는 두 개 이상의 leg가 필요합니다.");
        }
        List<AssignmentChange> changes = new ArrayList<>(legCopy.size());
        for (Leg leg : legCopy) {
            changes.add(new AssignmentChange(
                    leg.shiftIndex(), state.employeeIndex(leg.shiftIndex()), leg.newEmployeeIndex()));
        }
        return new ChainMove(MoveSupport.validateChanges(problem, changes));
    }

    @Override
    public String moveType() {
        return "CHAIN";
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
