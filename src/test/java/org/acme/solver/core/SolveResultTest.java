package org.acme.solver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SolveResultTest {

    @Test
    void acceptsEngineNeutralTerminalResult() {
        RosterScore score = RosterScore.of(0, -1, -2, -3, 4);
        SolveResult<String> result = new SolveResult<>(
                "solution", score, TerminationReason.CONVERGED, 2, 100, 250, 42);

        assertEquals("solution", result.bestSolution());
        assertEquals(score, result.score());
        assertEquals(TerminationReason.CONVERGED, result.terminationReason());
    }

    @Test
    void rejectsNonTerminalAndInconsistentOrNegativeMetadata() {
        RosterScore score = RosterScore.of(0, 0, 0, 0, 0);

        assertThrows(IllegalArgumentException.class,
                () -> new SolveResult<>("solution", score, TerminationReason.CONTINUE, 0, 0, 0, 42));
        assertThrows(IllegalArgumentException.class,
                () -> new SolveResult<>("solution", null, TerminationReason.COMPLETED, 0, 0, 0, 42));
        assertThrows(IllegalArgumentException.class,
                () -> new SolveResult<>(null, null, TerminationReason.COMPLETED, -1, 0, 0, 42));
    }
}
