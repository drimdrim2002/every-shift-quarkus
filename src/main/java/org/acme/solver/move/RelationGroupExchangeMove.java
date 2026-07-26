package org.acme.solver.move;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.acme.solver.core.PlanningProblem;

/**
 * relation group과 target 슬롯의 singleton 직원들을 source/target 사이에서 원자적으로 교환합니다.
 *
 * <p>두 슬롯의 모든 변경을 하나의 move에 담아 relation group이 partial 상태로 노출되지 않으며,
 * target에서 밀려난 직원도 같은 transaction 안에서 source 슬롯으로 완전히 재배정됩니다.</p>
 */
public final class RelationGroupExchangeMove implements Move {

    private final int relationGroupId;
    private final List<AssignmentChange> changes;

    private RelationGroupExchangeMove(int relationGroupId, List<AssignmentChange> changes) {
        this.relationGroupId = relationGroupId;
        this.changes = changes;
    }

    public static RelationGroupExchangeMove create(
            PlanningProblem problem,
            SearchState state,
            List<Integer> sourceShiftIndexes,
            List<Integer> targetShiftIndexes) {
        MoveSupport.validateProblem(problem, state);
        List<Integer> sourceShifts = validatedShiftIndexes(
                problem, sourceShiftIndexes, "sourceShiftIndexes");
        List<Integer> targetShifts = validatedShiftIndexes(
                problem, targetShiftIndexes, "targetShiftIndexes");
        if (sourceShifts.size() < 2 || sourceShifts.size() != targetShifts.size()) {
            throw new IllegalArgumentException(
                    "relation group exchange는 크기가 같은 두 개 이상의 source/target shift가 필요합니다.");
        }

        SlotKey sourceSlot = sameSlot(problem, sourceShifts, "source");
        SlotKey targetSlot = sameSlot(problem, targetShifts, "target");
        if (sourceSlot.equals(targetSlot)) {
            throw new IllegalArgumentException("relation group exchange의 source와 target 슬롯은 달라야 합니다.");
        }

        Set<Integer> allShifts = new HashSet<>(sourceShifts);
        Set<Integer> uniqueTargetShifts = new HashSet<>(targetShifts);
        if (allShifts.size() != sourceShifts.size()
                || uniqueTargetShifts.size() != targetShifts.size()
                || !java.util.Collections.disjoint(allShifts, uniqueTargetShifts)) {
            throw new IllegalArgumentException("source/target shift는 중복될 수 없습니다.");
        }

        PreceptorRelationIndex relations = new PreceptorRelationIndex(problem);
        List<Integer> relationEmployees = employeesAt(state, sourceShifts);
        Set<Integer> relationEmployeeSet = new HashSet<>(relationEmployees);
        int relationGroupId = relations.groupId(relationEmployees.getFirst());
        List<Integer> completeGroup = relations.employeesInGroup(relationGroupId);
        if (completeGroup.size() < 2
                || relationEmployeeSet.size() != relationEmployees.size()
                || !relationEmployeeSet.equals(new HashSet<>(completeGroup))) {
            throw new IllegalArgumentException(
                    "source 슬롯은 하나의 완전한 preceptor relation group이어야 합니다: " + completeGroup);
        }

        List<Integer> displacedEmployees = employeesAt(state, targetShifts);
        Set<Integer> displacedEmployeeSet = new HashSet<>(displacedEmployees);
        if (displacedEmployeeSet.size() != displacedEmployees.size()) {
            throw new IllegalArgumentException("target 슬롯에서 밀려나는 직원은 서로 달라야 합니다.");
        }
        for (int employeeIndex : displacedEmployees) {
            if (relationEmployeeSet.contains(employeeIndex)
                    || relations.employeesFor(employeeIndex).size() != 1) {
                throw new IllegalArgumentException(
                        "target 슬롯에서는 relation group이 아닌 singleton 직원만 교환할 수 있습니다: "
                                + employeeIndex);
            }
        }

        for (int employeeIndex : relationEmployees) {
            if (!canWorkShiftCode(problem, employeeIndex, targetShifts.getFirst())) {
                throw new IllegalArgumentException(
                        "relation 직원이 target shift code를 수행할 수 없습니다: " + employeeIndex);
            }
        }
        for (int employeeIndex : displacedEmployees) {
            if (!canWorkShiftCode(problem, employeeIndex, sourceShifts.getFirst())) {
                throw new IllegalArgumentException(
                        "target 직원이 source shift code를 수행할 수 없습니다: " + employeeIndex);
            }
        }

        List<AssignmentChange> changes = new ArrayList<>(sourceShifts.size() * 2);
        for (int index = 0; index < sourceShifts.size(); index++) {
            int sourceShift = sourceShifts.get(index);
            int targetShift = targetShifts.get(index);
            changes.add(new AssignmentChange(
                    sourceShift, relationEmployees.get(index), displacedEmployees.get(index)));
            changes.add(new AssignmentChange(
                    targetShift, displacedEmployees.get(index), relationEmployees.get(index)));
        }
        return new RelationGroupExchangeMove(
                relationGroupId, MoveSupport.validateChanges(problem, changes));
    }

    public int relationGroupId() {
        return relationGroupId;
    }

    @Override
    public String moveType() {
        return "RELATION_GROUP_EXCHANGE";
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

    private static List<Integer> validatedShiftIndexes(
            PlanningProblem problem, List<Integer> shiftIndexes, String fieldName) {
        List<Integer> copy = List.copyOf(Objects.requireNonNull(shiftIndexes, fieldName));
        for (int shiftIndex : copy) {
            if (shiftIndex < 0 || shiftIndex >= problem.shiftCount()) {
                throw new IllegalArgumentException(
                        fieldName + "의 shiftIndex 범위를 벗어났습니다: " + shiftIndex);
            }
        }
        return copy;
    }

    private static SlotKey sameSlot(
            PlanningProblem problem, List<Integer> shiftIndexes, String label) {
        SlotKey expected = SlotKey.of(problem.shifts().get(shiftIndexes.getFirst()));
        for (int shiftIndex : shiftIndexes) {
            if (!expected.equals(SlotKey.of(problem.shifts().get(shiftIndex)))) {
                throw new IllegalArgumentException(
                        label + " shift는 같은 실제일·shift code여야 합니다.");
            }
        }
        return expected;
    }

    private static List<Integer> employeesAt(SearchState state, List<Integer> shiftIndexes) {
        List<Integer> employees = new ArrayList<>(shiftIndexes.size());
        for (int shiftIndex : shiftIndexes) {
            employees.add(state.employeeIndex(shiftIndex));
        }
        return employees;
    }

    private static boolean canWorkShiftCode(
            PlanningProblem problem, int employeeIndex, int shiftIndex) {
        String shiftCode = normalize(problem.shifts().get(shiftIndex).shiftCode());
        return problem.employees().get(employeeIndex).availableShiftCodes().stream()
                .map(RelationGroupExchangeMove::normalize)
                .anyMatch(shiftCode::equals);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record SlotKey(LocalDate date, String shiftCode) {
        static SlotKey of(PlanningProblem.ShiftData shift) {
            return new SlotKey(shift.start().toLocalDate(), shift.shiftCode());
        }
    }
}
