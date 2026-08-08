package org.acme.solver.score;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.acme.solver.core.PlanningProblem;

public final class DesiredAssignmentConstraint implements ConstraintEvaluator {

    private static final ConstraintDependencyMetadata DEPENDENCIES = ConstraintDependencyMetadata.shift(
            "desired-assignment", DependencyDimension.ACTUAL_DATE);

    @Override
    public String evaluatorId() {
        return "desired-assignment";
    }

    @Override
    public ConstraintDependencyMetadata dependencyMetadata() {
        return DEPENDENCIES;
    }

    @Override
    public void evaluate(ScoreEvaluationContext context, ContributionCollector collector) {
        PlanningProblem problem = context.problem();
        List<Map<LocalDate, Integer>> desiredCounts = desiredCounts(problem);
        for (int shiftIndex : context.shiftIndexes()) {
            int employeeIndex = context.solution().employeeIndex(shiftIndex);
            PlanningProblem.ShiftData shift = problem.shifts().get(shiftIndex);
            int matchCount = desiredCounts.get(employeeIndex).getOrDefault(shift.start().toLocalDate(), 0);
            if (matchCount > 0) {
                long reward = Math.multiplyExact((long) ScoreSupport.durationMinutes(shift), matchCount);
                collector.reward(ConstraintIds.DESIRED, ScoreLevel.SOFT_2, reward,
                        List.of(employeeIndex), List.of(shiftIndex));
            }
        }
    }

    private static List<Map<LocalDate, Integer>> desiredCounts(PlanningProblem problem) {
        java.util.ArrayList<Map<LocalDate, Integer>> result = new java.util.ArrayList<>(problem.employeeCount());
        for (int employeeIndex = 0; employeeIndex < problem.employeeCount(); employeeIndex++) {
            result.add(new LinkedHashMap<>());
        }
        for (PlanningProblem.AvailabilityData availability : problem.availabilities()) {
            if (availability.kind() == PlanningProblem.AvailabilityKind.DESIRED) {
                result.get(availability.employeeIndex()).merge(availability.date(), 1, Math::addExact);
            }
        }
        return result;
    }
}
