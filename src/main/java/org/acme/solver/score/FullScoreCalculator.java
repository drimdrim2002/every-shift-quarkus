package org.acme.solver.score;

import java.util.List;
import java.util.Objects;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;

/**
 * Constraint Streams와 독립적인 순수 Java 전체 점수 계산기입니다.
 *
 * <p>complete assignment만 받으며 상태를 캐시하거나 증분 갱신하지 않습니다.</p>
 */
public final class FullScoreCalculator {

    private static final List<ConstraintEvaluator> DEFAULT_EVALUATORS = List.of(
            new RequiredSkillConstraint(),
            new OverlapConstraint(),
            new MinimumRestConstraint(),
            new ConsecutiveNightConstraint(),
            new MonthlyNightLimitConstraint(),
            new OneShiftPerDayConstraint(),
            new PreceptorPairConstraint(),
            new PostNightRecoveryConstraint(),
            new NightToDayRestPreference(),
            new UndesiredAssignmentConstraint(),
            new FairnessConstraint(),
            new DesiredAssignmentConstraint());

    private final List<ConstraintEvaluator> evaluators;

    public FullScoreCalculator() {
        this(DEFAULT_EVALUATORS);
    }

    public FullScoreCalculator(List<ConstraintEvaluator> evaluators) {
        this.evaluators = List.copyOf(Objects.requireNonNull(evaluators, "evaluators"));
        if (this.evaluators.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("evaluators에는 null을 포함할 수 없습니다.");
        }
    }

    public RosterScore calculateScore(PlanningProblem problem, RosterSolution solution) {
        return calculateWithBreakdown(problem, solution).score();
    }

    public ScoreCalculationResult calculateWithBreakdown(
            PlanningProblem problem, RosterSolution solution) {
        ScoreEvaluationContext context = new ScoreEvaluationContext(problem, solution);
        ContributionCollector collector = new ContributionCollector();
        for (ConstraintEvaluator evaluator : evaluators) {
            evaluator.evaluate(context, collector);
        }
        return collector.toResult();
    }

    public List<ConstraintEvaluator> evaluators() {
        return evaluators;
    }
}
