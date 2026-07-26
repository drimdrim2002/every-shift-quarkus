package org.acme.solver.score;

import java.time.Duration;
import java.util.List;

import org.acme.solver.core.PlanningProblem;

public final class OneShiftPerDayConstraint implements ConstraintEvaluator {

    private static final ConstraintDependencyMetadata DEPENDENCIES = ConstraintDependencyMetadata.employee(
            "one-shift-per-day", Duration.ZERO, Duration.ZERO, 0, 0,
            DependencyDimension.ACTUAL_DATE);

    @Override
    public String evaluatorId() {
        return "one-shift-per-day";
    }

    @Override
    public ConstraintDependencyMetadata dependencyMetadata() {
        return DEPENDENCIES;
    }

    @Override
    public void evaluate(ScoreEvaluationContext context, ContributionCollector collector) {
        PlanningProblem problem = context.problem();
        for (int employeeIndex : context.employeeIndexes()) {
            for (List<Integer> shifts : context.index().shiftsByActualDate(employeeIndex).values()) {
                for (int left = 0; left < shifts.size(); left++) {
                    for (int right = left + 1; right < shifts.size(); right++) {
                        collector.penalize(ConstraintIds.ONE_SHIFT_PER_DAY, ScoreLevel.HARD, 1L,
                                List.of(employeeIndex), ScoreSupport.pair(shifts.get(left), shifts.get(right)));
                    }
                }
            }
        }
    }
}
