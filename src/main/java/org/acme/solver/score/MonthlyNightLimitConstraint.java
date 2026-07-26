package org.acme.solver.score;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.acme.solver.core.PlanningProblem;

public final class MonthlyNightLimitConstraint implements ConstraintEvaluator {

    private static final int MAXIMUM_NIGHTS = 15;
    private static final ConstraintDependencyMetadata DEPENDENCIES = ConstraintDependencyMetadata.employee(
            "monthly-night-limit", Duration.ZERO, Duration.ZERO, 0, 0,
            DependencyDimension.ACTUAL_MONTH);

    @Override
    public String evaluatorId() {
        return "monthly-night-limit";
    }

    @Override
    public ConstraintDependencyMetadata dependencyMetadata() {
        return DEPENDENCIES;
    }

    @Override
    public void evaluate(ScoreEvaluationContext context, ContributionCollector collector) {
        PlanningProblem problem = context.problem();
        for (int employeeIndex : context.employeeIndexes()) {
            for (List<Integer> monthShifts : context.index().shiftsByActualMonth(employeeIndex).values()) {
                List<Integer> nights = new ArrayList<>();
                for (int shiftIndex : monthShifts) {
                    if (ScoreSupport.isNight(problem.shifts().get(shiftIndex))) {
                        nights.add(shiftIndex);
                    }
                }
                if (nights.size() > MAXIMUM_NIGHTS) {
                    collector.penalize(ConstraintIds.MONTHLY_NIGHT_LIMIT, ScoreLevel.HARD,
                            nights.size() - MAXIMUM_NIGHTS, List.of(employeeIndex), nights);
                }
            }
        }
    }
}
