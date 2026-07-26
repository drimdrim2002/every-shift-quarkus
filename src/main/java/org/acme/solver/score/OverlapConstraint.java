package org.acme.solver.score;

import java.time.Duration;
import java.util.List;

import org.acme.solver.core.PlanningProblem;

public final class OverlapConstraint implements ConstraintEvaluator {

    private static final ConstraintDependencyMetadata DEPENDENCIES = ConstraintDependencyMetadata.employee(
            "overlap", Duration.ZERO, Duration.ZERO, 0, 0,
            DependencyDimension.ACTUAL_DATE, DependencyDimension.ADJACENT_REST_WINDOW);

    @Override
    public String evaluatorId() {
        return "overlap";
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
                PlanningProblem.ShiftData first = problem.shifts().get(firstIndex);
                for (int right = left + 1; right < shifts.size(); right++) {
                    int secondIndex = shifts.get(right);
                    PlanningProblem.ShiftData second = problem.shifts().get(secondIndex);
                    if (first.start().isBefore(second.end()) && second.start().isBefore(first.end())) {
                        collector.penalize(ConstraintIds.OVERLAP, ScoreLevel.HARD,
                                ScoreSupport.overlapMinutes(first, second),
                                List.of(employeeIndex), ScoreSupport.pair(firstIndex, secondIndex));
                    }
                }
            }
        }
    }
}
