package org.acme.solver.score;

import java.util.List;

import org.acme.solver.core.PlanningProblem;

public final class RequiredSkillConstraint implements ConstraintEvaluator {

    private static final ConstraintDependencyMetadata DEPENDENCIES = ConstraintDependencyMetadata.shift(
            "required-skill");

    @Override
    public String evaluatorId() {
        return "required-skill";
    }

    @Override
    public ConstraintDependencyMetadata dependencyMetadata() {
        return DEPENDENCIES;
    }

    @Override
    public void evaluate(ScoreEvaluationContext context, ContributionCollector collector) {
        PlanningProblem problem = context.problem();
        for (int shiftIndex : context.shiftIndexes()) {
            int employeeIndex = context.solution().employeeIndex(shiftIndex);
            PlanningProblem.ShiftData shift = problem.shifts().get(shiftIndex);
            if (!problem.employees().get(employeeIndex).skillSet().contains(shift.requiredSkill())) {
                collector.penalize(ConstraintIds.REQUIRED_SKILL, ScoreLevel.HARD, 1L,
                        List.of(employeeIndex), List.of(shiftIndex));
            }
        }
    }
}
