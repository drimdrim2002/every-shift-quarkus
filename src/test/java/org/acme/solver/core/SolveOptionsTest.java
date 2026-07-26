package org.acme.solver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class SolveOptionsTest {

    @Test
    void warmStart와_종료_옵션을_불변_값으로_보존한다() {
        RosterSolution warmStart = new RosterSolution(
                1,
                new int[] { 0 },
                RosterScore.of(0, 0, 0, 0, 0));
        SolveOptions options = SolveOptions.builder()
                .spentLimit(Duration.ofSeconds(1))
                .maxEvaluations(100)
                .maxIterations(10)
                .maxStagnantEvaluations(5)
                .randomSeed(42)
                .warmStart(warmStart)
                .cancellationToken(() -> true)
                .build();

        assertEquals(Duration.ofSeconds(1), options.spentLimit().orElseThrow());
        assertEquals(100, options.maxEvaluations());
        assertEquals(10, options.maxIterations());
        assertEquals(5, options.maxStagnantEvaluations());
        assertEquals(42, options.randomSeed());
        assertEquals(warmStart, options.warmStart().orElseThrow());
        assertTrue(options.cancellationToken().isCancellationRequested());
        assertFalse(options.hasDeadline());
    }

    @Test
    void 잘못된_종료_한도를_거절한다() {
        assertThrows(IllegalArgumentException.class,
                () -> SolveOptions.builder().maxEvaluations(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> SolveOptions.builder().spentLimit(Duration.ZERO).build());
    }
}
