package org.acme.solver.alns;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.core.TerminationReason;
import org.junit.jupiter.api.Test;

class SolverDeadlineTest {

    @Test
    void wall_clock_profile은_search와_final_validation_예산을_분리하고_deadline을_준수한다() {
        AlnsTestSupport.Fixture fixture = AlnsTestSupport.skillFixture(true);
        AlnsSolverEngine engine = SolverDeterminismTest.engine(
                fixture, SolverDeterminismTest.config(4, Duration.ofMillis(5)));
        long started = System.nanoTime();

        SolveResult<RosterSolution> result = engine.solve(
                fixture.problem(),
                SolveOptions.builder()
                        .warmStart(fixture.initial())
                        .spentLimit(Duration.ofMillis(50))
                        .randomSeed(42L)
                        .build(),
                ignored -> {
                });
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertEquals(TerminationReason.DEADLINE_REACHED, result.terminationReason());
        assertTrue(elapsedMillis < 500L, "elapsedMillis=" + elapsedMillis);
        assertNotNull(result.bestSolution());
        assertEquals(0, result.score().hardScore());
        assertEquals(fixture.full().calculateScore(fixture.problem(), result.bestSolution()), result.score());
        AlnsRunMetrics metrics = assertInstanceOf(AlnsRunMetrics.class, result.metrics());
        assertEquals("WALL_CLOCK_PRODUCTION", metrics.profile());
        assertEquals(4, metrics.calibration().attempts());
        assertTrue(metrics.finalValidationPerformed());
    }

    @Test
    void iteration_중_cancellation은_transaction을_rollback하고_마지막_verified_feasible_best만_반환한다() {
        AlnsTestSupport.Fixture fixture = AlnsTestSupport.skillFixture(true);
        AtomicInteger checks = new AtomicInteger();
        SolveOptions options = SolveOptions.builder()
                .warmStart(fixture.initial())
                .maxEvaluations(100L)
                .randomSeed(42L)
                .cancellationToken(() -> checks.incrementAndGet() >= 5)
                .build();

        SolveResult<RosterSolution> result = SolverDeterminismTest.engine(
                fixture, SolverDeterminismTest.config(0, Duration.ZERO)).solve(
                        fixture.problem(), options, ignored -> {
                        });

        assertEquals(TerminationReason.CANCELLED, result.terminationReason());
        assertNotNull(result.bestSolution());
        assertEquals(0, result.score().hardScore());
        assertArrayEquals(
                fixture.initial().employeeIndexByShift(),
                result.bestSolution().employeeIndexByShift());
        assertEquals(fixture.full().calculateScore(fixture.problem(), result.bestSolution()), result.score());
    }
}
