package org.acme.solver.score;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.acme.solver.core.PlanningProblem;

/**
 * soft[2]의 야간·휴일·주/저녁 세 Constraint Streams를 동일한 제곱 산식으로 평가합니다.
 */
public final class FairnessConstraint implements ConstraintEvaluator {

    private static final ConstraintDependencyMetadata DEPENDENCIES = ConstraintDependencyMetadata.employee(
            "fairness", Duration.ZERO, Duration.ZERO, 0, 0,
            DependencyDimension.FAIRNESS_AGGREGATE);

    @Override
    public String evaluatorId() {
        return "fairness";
    }

    @Override
    public ConstraintDependencyMetadata dependencyMetadata() {
        return DEPENDENCIES;
    }

    @Override
    public void evaluate(ScoreEvaluationContext context, ContributionCollector collector) {
        PlanningProblem problem = context.problem();
        for (int employeeIndex : context.employeeIndexes()) {
            evaluateNight(context, collector, employeeIndex);
            evaluateHoliday(context, collector, employeeIndex);
            evaluateDayEvening(context, collector, employeeIndex);
        }
    }

    private static void evaluateNight(
            ScoreEvaluationContext context, ContributionCollector collector, int employeeIndex) {
        PlanningProblem problem = context.problem();
        List<Integer> nights = context.index().shiftsByType(employeeIndex)
                .getOrDefault(RosterIndex.SHIFT_TYPE_NIGHT, List.of());
        if (nights.isEmpty()) {
            return;
        }
        long burden = 0L;
        for (int shiftIndex : nights) {
            burden = Math.addExact(burden, problem.shifts().get(shiftIndex).nightBurdenScore());
        }
        collector.penalize(ConstraintIds.NIGHT_FAIRNESS, ScoreLevel.SOFT_2,
                Math.multiplyExact(burden, burden), List.of(employeeIndex), nights);
    }

    private static void evaluateHoliday(
            ScoreEvaluationContext context, ContributionCollector collector, int employeeIndex) {
        PlanningProblem problem = context.problem();
        List<Integer> burdenShifts = new ArrayList<>();
        long burden = 0L;
        for (int shiftIndex : context.index().shiftsByEmployee(employeeIndex)) {
            int shiftBurden = problem.shifts().get(shiftIndex).holidayBurdenScore();
            if (shiftBurden > 0) {
                burdenShifts.add(shiftIndex);
                burden = Math.addExact(burden, shiftBurden);
            }
        }
        if (!burdenShifts.isEmpty()) {
            collector.penalize(ConstraintIds.HOLIDAY_FAIRNESS, ScoreLevel.SOFT_2,
                    Math.multiplyExact(burden, burden), List.of(employeeIndex), burdenShifts);
        }
    }

    private static void evaluateDayEvening(
            ScoreEvaluationContext context, ContributionCollector collector, int employeeIndex) {
        PlanningProblem problem = context.problem();
        Map<String, List<Integer>> byType = new LinkedHashMap<>();
        for (int shiftIndex : context.index().shiftsByEmployee(employeeIndex)) {
            String shiftType = RosterIndex.normalizeShiftType(problem.shifts().get(shiftIndex).shiftCode());
            if (!RosterIndex.SHIFT_TYPE_NIGHT.equals(shiftType)) {
                byType.computeIfAbsent(shiftType, ignored -> new ArrayList<>()).add(shiftIndex);
            }
        }
        for (var entry : byType.entrySet()) {
            long count = entry.getValue().size();
            long weight = RosterIndex.SHIFT_TYPE_EVENING.equals(entry.getKey()) ? 5L : 1L;
            long magnitude = Math.multiplyExact(weight, Math.multiplyExact(count, count));
            collector.penalize(ConstraintIds.DAY_EVENING_FAIRNESS, ScoreLevel.SOFT_2,
                    magnitude, List.of(employeeIndex), entry.getValue());
        }
    }
}
