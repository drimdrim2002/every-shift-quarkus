package org.acme.solver.alns;

import java.time.LocalDate;
import java.util.List;

import org.acme.solver.core.PlanningProblem;

/** relation group의 기존 실제일·교대 결합을 먼저 원자 복원한 뒤 greedy로 마무리합니다. */
public final class RelationAwareRepair implements RepairOperator {

    public static final String ID = "RELATION_AWARE_REPAIR";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RepairResult repair(RepairContext context) {
        while (context.beginAttempt()) {
            restoreRemovedRelationBundles(context);
            if (RepairOperatorSupport.fillGreedy(context)) {
                return RepairResult.completed(context.attempts());
            }
        }
        return RepairResult.failed(context.attempts());
    }

    private static void restoreRemovedRelationBundles(RepairContext context) {
        int[] removed = context.unassignedShiftIndexes();
        for (int seedShiftIndex : removed) {
            if (context.assignment(seedShiftIndex) >= 0) {
                continue;
            }
            int originalEmployee = context.originalEmployeeIndex(seedShiftIndex);
            List<Integer> relationEmployees = context.relationEmployeesFor(originalEmployee);
            if (relationEmployees.size() <= 1) {
                continue;
            }
            PlanningProblem.ShiftData seed = context.problem().shifts().get(seedShiftIndex);
            LocalDate date = seed.start().toLocalDate();
            String shiftCode = DestroyContext.normalize(seed.shiftCode());
            for (int shiftIndex : context.unassignedShiftIndexes()) {
                PlanningProblem.ShiftData candidate = context.problem().shifts().get(shiftIndex);
                int candidateOriginalEmployee = context.originalEmployeeIndex(shiftIndex);
                if (candidate.start().toLocalDate().equals(date)
                        && DestroyContext.normalize(candidate.shiftCode()).equals(shiftCode)
                        && relationEmployees.contains(candidateOriginalEmployee)) {
                    context.assign(shiftIndex, candidateOriginalEmployee);
                }
            }
        }
    }
}
