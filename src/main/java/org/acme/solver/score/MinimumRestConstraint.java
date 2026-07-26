package org.acme.solver.score;

import java.time.Duration;
import java.util.List;

import org.acme.solver.core.PlanningProblem;

public final class MinimumRestConstraint implements ConstraintEvaluator {

    private static final int MINIMUM_REST_MINUTES = 12 * 60;
    private static final ConstraintDependencyMetadata DEPENDENCIES = ConstraintDependencyMetadata.employee(
            "minimum-rest", Duration.ofHours(12), Duration.ofHours(12), 0, 0,
            DependencyDimension.ACTUAL_DATE, DependencyDimension.ADJACENT_REST_WINDOW);

    @Override
    public String evaluatorId() {
        return "minimum-rest";
    }

    @Override
    public ConstraintDependencyMetadata dependencyMetadata() {
        return DEPENDENCIES;
    }

    @Override
    public void evaluate(ScoreEvaluationContext context, ContributionCollector collector) {
        PlanningProblem problem = context.problem();
        for (int employeeIndex : context.employeeIndexes()) {
            List<Integer> shifts = context.index().shiftsByEmployee(employeeIndex);
            for (int left = 0; left < shifts.size(); left++) {
                int firstIndex = shifts.get(left);
                for (int right = left + 1; right < shifts.size(); right++) {
                    int secondIndex = shifts.get(right);
                    int rest = ScoreSupport.breakMinutes(
                            problem.shifts().get(firstIndex), problem.shifts().get(secondIndex));
                    if (rest >= 0 && rest < MINIMUM_REST_MINUTES) {
                        collector.penalize(ConstraintIds.MINIMUM_REST, ScoreLevel.HARD,
                                MINIMUM_REST_MINUTES - rest,
                                List.of(employeeIndex), ScoreSupport.pair(firstIndex, secondIndex));
                    }
                }
            }
        }
    }
}
