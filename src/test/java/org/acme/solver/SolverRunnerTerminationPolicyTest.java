package org.acme.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.acme.solver.core.RosterScore;
import org.acme.solver.core.TerminationReason;
import org.junit.jupiter.api.Test;

class SolverRunnerTerminationPolicyTest {

    @Test
    void deadlineStopsSolverEvenWhenNotConverged() {
        SolverRunner solverRunner = new SolverRunner();
        solverRunner.minIterations = 2;
        solverRunner.maxIterations = 30;

        RosterScore previousScore = RosterScore.of(0, 0, 0, -11, 0);
        RosterScore currentScore = RosterScore.of(0, 0, 0, -10, 0);

        TerminationReason reason = solverRunner.determineTerminationReason(
                2,
                currentScore,
                previousScore,
                1_001L,
                1_000L);

        assertEquals(TerminationReason.DEADLINE_REACHED, reason);
    }

    @Test
    void maxIterationsStopsSolverWhenConfigured() {
        SolverRunner solverRunner = new SolverRunner();
        solverRunner.minIterations = 2;
        solverRunner.maxIterations = 3;

        RosterScore previousScore = RosterScore.of(0, 0, 0, -11, 0);
        RosterScore currentScore = RosterScore.of(0, 0, 0, -10, 0);

        TerminationReason reason = solverRunner.determineTerminationReason(
                3,
                currentScore,
                previousScore,
                900L,
                1_000L);

        assertEquals(TerminationReason.MAX_ITERATIONS_REACHED, reason);
    }
}
