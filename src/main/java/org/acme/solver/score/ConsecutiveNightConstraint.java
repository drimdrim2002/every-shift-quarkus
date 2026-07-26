package org.acme.solver.score;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.acme.solver.core.PlanningProblem;

public final class ConsecutiveNightConstraint implements ConstraintEvaluator {

    private static final ConstraintDependencyMetadata DEPENDENCIES = ConstraintDependencyMetadata.employee(
            "consecutive-night", Duration.ZERO, Duration.ZERO, 3, 3,
            DependencyDimension.LOGICAL_DATE);

    @Override
    public String evaluatorId() {
        return "consecutive-night";
    }

    @Override
    public ConstraintDependencyMetadata dependencyMetadata() {
        return DEPENDENCIES;
    }

    @Override
    public void evaluate(ScoreEvaluationContext context, ContributionCollector collector) {
        PlanningProblem problem = context.problem();
        for (int employeeIndex : context.employeeIndexes()) {
            for (var entry : context.index().shiftsByLogicalDate(employeeIndex).entrySet()) {
                LocalDate firstDate = entry.getKey();
                List<Integer> firstNights = ScoreSupport.nightShiftsOnLogicalDate(context, employeeIndex, firstDate);
                if (firstNights.isEmpty()) {
                    continue;
                }
                List<Integer> secondNights = ScoreSupport.nightShiftsOnLogicalDate(
                        context, employeeIndex, firstDate.plusDays(1));
                List<Integer> thirdNights = ScoreSupport.nightShiftsOnLogicalDate(
                        context, employeeIndex, firstDate.plusDays(2));
                List<Integer> fourthNights = ScoreSupport.nightShiftsOnLogicalDate(
                        context, employeeIndex, firstDate.plusDays(3));
                for (int first : firstNights) {
                    for (int second : secondNights) {
                        for (int third : thirdNights) {
                            for (int fourth : fourthNights) {
                                collector.penalize(ConstraintIds.CONSECUTIVE_NIGHT, ScoreLevel.HARD, 1L,
                                        List.of(employeeIndex), List.of(first, second, third, fourth));
                            }
                        }
                    }
                }
            }
        }
    }
}
