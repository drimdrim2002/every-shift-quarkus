package org.acme.solver.alns;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.core.TerminationReason;
import org.acme.solver.initial.InitialSolutionBuilder;
import org.acme.solver.score.ConstraintDependencyMetadata;
import org.acme.solver.score.ConstraintEvaluator;
import org.acme.solver.score.ContributionCollector;
import org.acme.solver.score.DependencyDimension;
import org.acme.solver.score.FullScoreCalculator;
import org.acme.solver.score.ScoreEvaluationContext;
import org.acme.solver.score.ScoreLevel;
import org.junit.jupiter.api.Test;

class AlnsFailureTerminationTest {

    @Test
    void SCORE_MISMATCH는_rollback_후_마지막_verified_feasible_best로_안전_종료한다() {
        AlnsTestSupport.Fixture fixture = AlnsTestSupport.skillFixture(true);
        AtomicBoolean mismatchNextFullEvaluation = new AtomicBoolean();
        FullScoreCalculator full = new FullScoreCalculator(
                List.of(new OneShotFullMismatchEvaluator(mismatchNextFullEvaluation)));
        RosterSolution initial = new RosterSolution(
                fixture.problem().employeeCount(),
                fixture.initial().employeeIndexByShift(),
                RosterScore.of(0, 0, 0, 0, 0));
        DestroyOperator destroy = new DestroyOperator() {
            @Override
            public String id() {
                return "FIXED_DESTROY";
            }

            @Override
            public DestroyPlan destroy(DestroyContext context) {
                return DestroyPlan.of(0);
            }
        };
        RepairOperator repair = new RepairOperator() {
            @Override
            public String id() {
                return "MISMATCH_REPAIR";
            }

            @Override
            public RepairResult repair(RepairContext context) {
                context.beginAttempt();
                context.assign(0, 1);
                mismatchNextFullEvaluation.set(true);
                return RepairResult.completed(context.attempts());
            }
        };
        OperatorCompatibilityMatrix matrix = new OperatorCompatibilityMatrix(
                Map.of(destroy.id(), Set.of(repair.id())));
        AlnsSolverConfig config = new AlnsSolverConfig(
                new AlnsIterationConfig(1.0d, 1, 1, 1, 1),
                new SaAcceptanceConfig(0.2d, 0.01d, 10L),
                new AdaptiveOperatorConfig(1, 0.1, 0.2, 10, 10, 5, 1, 0),
                0L,
                0,
                Duration.ZERO);
        AlnsSolverEngine engine = new AlnsSolverEngine(
                new InitialSolutionBuilder(full),
                full,
                List.of(destroy),
                List.of(repair),
                matrix,
                config);

        SolveResult<RosterSolution> result = engine.solve(
                fixture.problem(),
                SolveOptions.builder()
                        .warmStart(initial)
                        .maxEvaluations(1L)
                        .randomSeed(42L)
                        .build(),
                ignored -> {
                });

        assertEquals(TerminationReason.SCORE_MISMATCH, result.terminationReason());
        assertNotNull(result.bestSolution());
        assertArrayEquals(initial.employeeIndexByShift(), result.bestSolution().employeeIndexByShift());
        assertEquals(initial.score(), result.score());
        assertEquals(full.calculateScore(fixture.problem(), result.bestSolution()), result.score());
        AlnsRunMetrics metrics = (AlnsRunMetrics) result.metrics();
        assertEquals(1L, metrics.scoreMismatchFailures());
        assertEquals(0L, metrics.rollbackFailureCount());
    }

    private static final class OneShotFullMismatchEvaluator implements ConstraintEvaluator {

        private final AtomicBoolean mismatchNextFullEvaluation;

        private OneShotFullMismatchEvaluator(AtomicBoolean mismatchNextFullEvaluation) {
            this.mismatchNextFullEvaluation = mismatchNextFullEvaluation;
        }

        @Override
        public String evaluatorId() {
            return "one-shot-full-mismatch";
        }

        @Override
        public ConstraintDependencyMetadata dependencyMetadata() {
            return ConstraintDependencyMetadata.employee(
                    evaluatorId(),
                    Duration.ZERO,
                    Duration.ZERO,
                    0,
                    0,
                    DependencyDimension.FAIRNESS_AGGREGATE);
        }

        @Override
        public void evaluate(ScoreEvaluationContext context, ContributionCollector collector) {
            boolean fullEvaluation = context.employeeIndexes().length
                    == context.problem().employeeCount();
            if (fullEvaluation && mismatchNextFullEvaluation.compareAndSet(true, false)) {
                collector.penalize(
                        evaluatorId(), ScoreLevel.HARD, 1L, List.of(), List.of());
            }
        }
    }
}
