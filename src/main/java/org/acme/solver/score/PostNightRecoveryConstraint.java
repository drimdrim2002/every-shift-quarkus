package org.acme.solver.score;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.acme.solver.core.PlanningProblem;

public final class PostNightRecoveryConstraint implements ConstraintEvaluator {

    private static final int MINIMUM_RECOVERY_MINUTES = 48 * 60;
    private static final ConstraintDependencyMetadata DEPENDENCIES = ConstraintDependencyMetadata.employee(
            "post-night-recovery", Duration.ofHours(48), Duration.ofHours(48), 2, 2,
            DependencyDimension.ACTUAL_DATE,
            DependencyDimension.LOGICAL_DATE,
            DependencyDimension.ADJACENT_REST_WINDOW);

    @Override
    public String evaluatorId() {
        return "post-night-recovery";
    }

    @Override
    public ConstraintDependencyMetadata dependencyMetadata() {
        return DEPENDENCIES;
    }

    @Override
    public void evaluate(ScoreEvaluationContext context, ContributionCollector collector) {
        PlanningProblem problem = context.problem();
        for (int employeeIndex : context.employeeIndexes()) {
            for (int secondNightIndex : context.index().shiftsByEmployee(employeeIndex)) {
                PlanningProblem.ShiftData secondNight = problem.shifts().get(secondNightIndex);
                if (!ScoreSupport.isNight(secondNight)) {
                    continue;
                }
                LocalDate secondLogicalDate = secondNight.logicalDate();
                List<Integer> firstNights = ScoreSupport.nightShiftsOnLogicalDate(
                        context, employeeIndex, secondLogicalDate.minusDays(1));
                if (firstNights.isEmpty()) {
                    continue;
                }
                if (!ScoreSupport.nightShiftsOnLogicalDate(
                        context, employeeIndex, secondLogicalDate.plusDays(1)).isEmpty()) {
                    continue;
                }

                int nextShiftIndex = -1;
                LocalDateTime nextStart = null;
                for (int candidateIndex : context.index().shiftsByEmployee(employeeIndex)) {
                    LocalDateTime candidateStart = problem.shifts().get(candidateIndex).start();
                    if (!candidateStart.isBefore(secondNight.end())
                            && (nextStart == null || candidateStart.isBefore(nextStart))) {
                        nextShiftIndex = candidateIndex;
                        nextStart = candidateStart;
                    }
                }
                if (nextShiftIndex >= 0
                        && ScoreSupport.minutesBetween(secondNight.end(), nextStart) < MINIMUM_RECOVERY_MINUTES) {
                    collector.penalize(ConstraintIds.POST_NIGHT_RECOVERY, ScoreLevel.HARD, 1L,
                            List.of(employeeIndex), List.of(secondNightIndex, nextShiftIndex));
                }
            }
        }
    }
}
