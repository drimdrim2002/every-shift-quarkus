package org.acme.solver.score;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.acme.solver.core.PlanningProblem;

public final class NightToDayRestPreference implements ConstraintEvaluator {

    private static final int MINIMUM_REST_MINUTES = 32 * 60;
    private static final ConstraintDependencyMetadata DEPENDENCIES = ConstraintDependencyMetadata.employee(
            "night-to-day-rest", Duration.ofHours(32), Duration.ofHours(32), 1, 1,
            DependencyDimension.ACTUAL_DATE,
            DependencyDimension.LOGICAL_DATE,
            DependencyDimension.ADJACENT_REST_WINDOW);

    @Override
    public String evaluatorId() {
        return "night-to-day-rest";
    }

    @Override
    public ConstraintDependencyMetadata dependencyMetadata() {
        return DEPENDENCIES;
    }

    @Override
    public void evaluate(ScoreEvaluationContext context, ContributionCollector collector) {
        PlanningProblem problem = context.problem();
        for (int employeeIndex : context.employeeIndexes()) {
            for (int nightIndex : context.index().shiftsByType(employeeIndex)
                    .getOrDefault(RosterIndex.SHIFT_TYPE_NIGHT, List.of())) {
                PlanningProblem.ShiftData night = problem.shifts().get(nightIndex);
                int nextDayIndex = -1;
                LocalDateTime nextDayStart = null;
                for (int candidateIndex : context.index().shiftsByType(employeeIndex)
                        .getOrDefault(RosterIndex.SHIFT_TYPE_DAY, List.of())) {
                    LocalDateTime candidateStart = problem.shifts().get(candidateIndex).start();
                    if (candidateStart.isAfter(night.end())
                            && (nextDayStart == null || candidateStart.isBefore(nextDayStart))) {
                        nextDayIndex = candidateIndex;
                        nextDayStart = candidateStart;
                    }
                }
                if (nextDayIndex >= 0) {
                    int rest = ScoreSupport.minutesBetween(night.end(), nextDayStart);
                    if (rest < MINIMUM_REST_MINUTES) {
                        collector.penalize(ConstraintIds.NIGHT_TO_DAY_REST, ScoreLevel.SOFT_0,
                                MINIMUM_REST_MINUTES - rest,
                                List.of(employeeIndex), List.of(nightIndex, nextDayIndex));
                    }
                }
            }
        }
    }
}
