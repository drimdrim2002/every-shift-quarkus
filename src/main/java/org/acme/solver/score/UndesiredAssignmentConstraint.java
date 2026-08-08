package org.acme.solver.score;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.acme.solver.ShiftDateMatcher;
import org.acme.solver.core.PlanningProblem;

public final class UndesiredAssignmentConstraint implements ConstraintEvaluator {

    private static final ConstraintDependencyMetadata DEPENDENCIES = ConstraintDependencyMetadata.shift(
            "undesired-assignment",
            DependencyDimension.ACTUAL_DATE,
            DependencyDimension.LOGICAL_DATE);

    @Override
    public String evaluatorId() {
        return "undesired-assignment";
    }

    @Override
    public ConstraintDependencyMetadata dependencyMetadata() {
        return DEPENDENCIES;
    }

    @Override
    public void evaluate(ScoreEvaluationContext context, ContributionCollector collector) {
        PlanningProblem problem = context.problem();
        List<Set<LocalDate>> undesiredDates = availabilityDates(problem, PlanningProblem.AvailabilityKind.UNDESIRED);
        for (int shiftIndex : context.shiftIndexes()) {
            PlanningProblem.ShiftData shift = problem.shifts().get(shiftIndex);
            if (shift.explicitlyPinned()) {
                continue;
            }
            int employeeIndex = context.solution().employeeIndex(shiftIndex);
            boolean matched = false;
            for (LocalDate undesiredDate : undesiredDates.get(employeeIndex)) {
                if (ShiftDateMatcher.matchesActualOrLogicalDate(
                        shift.start(), shift.end(), shift.shiftCode(), undesiredDate)) {
                    matched = true;
                    break;
                }
            }
            if (matched) {
                long magnitude = Math.multiplyExact(
                        (long) ScoreSupport.durationMinutes(shift),
                        problem.employees().get(employeeIndex).offRequestPenaltyWeight());
                collector.penalize(ConstraintIds.UNDESIRED, ScoreLevel.SOFT_0, magnitude,
                        List.of(employeeIndex), List.of(shiftIndex));
            }
        }
    }

    private static List<Set<LocalDate>> availabilityDates(
            PlanningProblem problem, PlanningProblem.AvailabilityKind kind) {
        java.util.ArrayList<Set<LocalDate>> result = new java.util.ArrayList<>(problem.employeeCount());
        for (int employeeIndex = 0; employeeIndex < problem.employeeCount(); employeeIndex++) {
            result.add(new HashSet<>());
        }
        for (PlanningProblem.AvailabilityData availability : problem.availabilities()) {
            if (availability.kind() == kind) {
                result.get(availability.employeeIndex()).add(availability.date());
            }
        }
        return result;
    }
}
