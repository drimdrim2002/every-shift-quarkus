package org.acme.solver.score;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.acme.solver.core.PlanningProblem;

/**
 * 기존의 양방향 preceptor/preceptee Constraint Streams 두 개를 함께 평가합니다.
 */
public final class PreceptorPairConstraint implements ConstraintEvaluator {

    private static final ConstraintDependencyMetadata DEPENDENCIES = ConstraintDependencyMetadata.employee(
            "preceptor-pair", Duration.ZERO, Duration.ZERO, 0, 0,
            DependencyDimension.ACTUAL_DATE, DependencyDimension.PRECEPTOR_RELATION_GROUP);

    @Override
    public String evaluatorId() {
        return "preceptor-pair";
    }

    @Override
    public ConstraintDependencyMetadata dependencyMetadata() {
        return DEPENDENCIES;
    }

    @Override
    public void evaluate(ScoreEvaluationContext context, ContributionCollector collector) {
        PlanningProblem problem = context.problem();
        Map<String, List<Integer>> precepteesByPreceptorId = precepteesByPreceptorId(problem);

        for (int shiftIndex : context.shiftIndexes()) {
            int employeeIndex = context.solution().employeeIndex(shiftIndex);
            PlanningProblem.EmployeeData employee = problem.employees().get(employeeIndex);
            PlanningProblem.ShiftData shift = problem.shifts().get(shiftIndex);
            LocalDate actualDate = shift.start().toLocalDate();

            if (employee.preceptorExternalId() != null) {
                Integer preceptorIndex = problem.employeeIndexByExternalId().get(employee.preceptorExternalId());
                if (preceptorIndex == null
                        || !worksSameShift(context, preceptorIndex, actualDate, shift.shiftCode())) {
                    List<Integer> affectedEmployees = preceptorIndex == null
                            ? List.of(employeeIndex)
                            : List.of(employeeIndex, preceptorIndex);
                    collector.penalize(ConstraintIds.PRECEPTEE_PAIR, ScoreLevel.HARD, 1L,
                            affectedEmployees, List.of(shiftIndex));
                }
            }

            List<Integer> precepteeIndexes = precepteesByPreceptorId.getOrDefault(employee.externalId(), List.of());
            for (int precepteeIndex : precepteeIndexes) {
                if (!worksSameShift(context, precepteeIndex, actualDate, shift.shiftCode())) {
                    collector.penalize(ConstraintIds.PRECEPTOR_PAIR, ScoreLevel.HARD, 1L,
                            List.of(employeeIndex, precepteeIndex), List.of(shiftIndex));
                }
            }
        }
    }

    private static boolean worksSameShift(
            ScoreEvaluationContext context, int employeeIndex, LocalDate actualDate, String shiftCode) {
        for (int shiftIndex : context.index().shiftsByActualDate(employeeIndex, actualDate)) {
            if (Objects.equals(context.problem().shifts().get(shiftIndex).shiftCode(), shiftCode)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, List<Integer>> precepteesByPreceptorId(PlanningProblem problem) {
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        for (int employeeIndex = 0; employeeIndex < problem.employeeCount(); employeeIndex++) {
            String preceptorId = problem.employees().get(employeeIndex).preceptorExternalId();
            if (preceptorId != null) {
                result.computeIfAbsent(preceptorId, ignored -> new ArrayList<>()).add(employeeIndex);
            }
        }
        return result;
    }
}
