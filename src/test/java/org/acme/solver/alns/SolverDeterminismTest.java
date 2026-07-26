package org.acme.solver.alns;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.core.TerminationReason;
import org.acme.solver.initial.InitialSolutionBuilder;
import org.junit.jupiter.api.Test;

class SolverDeterminismTest {

    @Test
    void 동일_seed_평가예산_config는_assignment_score_operator학습을_정확히_재현한다() {
        AlnsTestSupport.Fixture fixture = AlnsTestSupport.skillFixture(true);
        AlnsSolverConfig config = config(5, Duration.ZERO);
        SolveOptions options = SolveOptions.builder()
                .warmStart(fixture.initial())
                .maxEvaluations(30L)
                .randomSeed(20260716L)
                .build();

        SolveResult<RosterSolution> first = engine(fixture, config).solve(
                fixture.problem(), options, ignored -> {
                });
        SolveResult<RosterSolution> second = engine(fixture, config).solve(
                fixture.problem(), options, ignored -> {
                });

        assertEquals(TerminationReason.MAX_EVALUATIONS_REACHED, first.terminationReason());
        assertEquals(first.terminationReason(), second.terminationReason());
        assertEquals(first.score(), second.score());
        assertArrayEquals(
                first.bestSolution().employeeIndexByShift(),
                second.bestSolution().employeeIndexByShift());
        assertEquals(first.evaluationCount(), second.evaluationCount());
        AlnsRunMetrics firstMetrics = assertInstanceOf(AlnsRunMetrics.class, first.metrics());
        AlnsRunMetrics secondMetrics = assertInstanceOf(AlnsRunMetrics.class, second.metrics());
        assertEquals("FIXED_EVALUATION_DETERMINISTIC", firstMetrics.profile());
        assertEquals(config.calibrationAttemptBudget(), firstMetrics.calibration().attempts());
        assertEquals(firstMetrics.destroyOperators(), secondMetrics.destroyOperators());
        assertEquals(firstMetrics.repairOperators(), secondMetrics.repairOperators());
        assertEquals(firstMetrics.bestImprovements(), secondMetrics.bestImprovements());
        assertEquals(firstMetrics.acceptedCandidates(), secondMetrics.acceptedCandidates());
        assertEquals(firstMetrics.rejectedCandidates(), secondMetrics.rejectedCandidates());
        assertTrue(firstMetrics.finalValidationPerformed());
        assertNotNull(first.bestSolution());
        assertEquals(0, first.score().hardScore());
    }

    static AlnsSolverEngine engine(
            AlnsTestSupport.Fixture fixture, AlnsSolverConfig config) {
        return new AlnsSolverEngine(
                new InitialSolutionBuilder(fixture.full()),
                fixture.full(),
                List.of(
                        new RandomRemoval(),
                        new RelatedShiftRemoval(),
                        new PreceptorRelationGroupRemoval()),
                List.of(
                        new GreedyRepair(),
                        new Regret2Repair(),
                        new RelationAwareRepair()),
                OperatorCompatibilityMatrix.baseline(),
                config);
    }

    static AlnsSolverConfig config(int calibrationAttempts, Duration reserve) {
        return new AlnsSolverConfig(
                new AlnsIterationConfig(1.0d, 1, 1, 1, 2),
                new SaAcceptanceConfig(0.2d, 0.01d, 100L),
                new AdaptiveOperatorConfig(1.0d, 0.1d, 0.2d, 5, 10, 5, 1, 0),
                10_000L,
                calibrationAttempts,
                reserve);
    }
}
