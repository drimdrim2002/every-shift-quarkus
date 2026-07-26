package org.acme.solver.move;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.acme.solver.core.PlanningProblem;

/**
 * 같은 실제일/교대의 shift 묶음을 하나의 완전한 preceptor relation group에 원자적으로 재배정합니다.
 */
public final class RelationGroupReassignMove implements Move {

    private final int relationGroupId;
    private final List<AssignmentChange> changes;

    private RelationGroupReassignMove(int relationGroupId, List<AssignmentChange> changes) {
        this.relationGroupId = relationGroupId;
        this.changes = changes;
    }

    public static RelationGroupReassignMove create(
            PlanningProblem problem,
            SearchState state,
            Map<Integer, Integer> newEmployeeByShift) {
        MoveSupport.validateProblem(problem, state);
        Map<Integer, Integer> assignments = new LinkedHashMap<>(
                Objects.requireNonNull(newEmployeeByShift, "newEmployeeByShift"));
        if (assignments.size() < 2) {
            throw new IllegalArgumentException("relation group move에는 두 개 이상의 shift가 필요합니다.");
        }

        LocalDate actualDate = null;
        String shiftCode = null;
        Set<Integer> newEmployees = new HashSet<>();
        List<AssignmentChange> changes = new ArrayList<>(assignments.size());
        for (var entry : assignments.entrySet()) {
            int shiftIndex = entry.getKey();
            int newEmployeeIndex = entry.getValue();
            PlanningProblem.ShiftData shift = problem.shifts().get(shiftIndex);
            if (actualDate == null) {
                actualDate = shift.start().toLocalDate();
                shiftCode = shift.shiftCode();
            } else if (!actualDate.equals(shift.start().toLocalDate())
                    || !Objects.equals(shiftCode, shift.shiftCode())) {
                throw new IllegalArgumentException("relation group shift는 같은 실제일/교대여야 합니다.");
            }
            if (!newEmployees.add(newEmployeeIndex)) {
                throw new IllegalArgumentException("relation group 직원은 중복될 수 없습니다: " + newEmployeeIndex);
            }
            changes.add(new AssignmentChange(
                    shiftIndex, state.employeeIndex(shiftIndex), newEmployeeIndex));
        }

        PreceptorRelationIndex relationIndex = new PreceptorRelationIndex(problem);
        int firstEmployee = newEmployees.iterator().next();
        int groupId = relationIndex.groupId(firstEmployee);
        List<Integer> groupEmployees = relationIndex.employeesInGroup(groupId);
        if (groupEmployees.size() < 2 || !newEmployees.equals(new HashSet<>(groupEmployees))) {
            throw new IllegalArgumentException(
                    "새 assignment는 하나의 완전한 preceptor relation group이어야 합니다: " + groupEmployees);
        }
        return new RelationGroupReassignMove(
                groupId, MoveSupport.validateChanges(problem, changes));
    }

    public int relationGroupId() {
        return relationGroupId;
    }

    @Override
    public String moveType() {
        return "RELATION_GROUP_REASSIGN";
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
