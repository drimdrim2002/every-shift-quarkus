package org.acme.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.score.buildin.bendable.BendableScore;

class SolverRunnerTerminationPolicyTest {

    @Test
    void deadlineStopsSolverEvenWhenNotConverged() {
        SolverRunner solverRunner = new SolverRunner();
        solverRunner.minIterations = 2;
        solverRunner.maxIterations = 30;

        BendableScore previousScore = BendableScore.parseScore("[0]hard/[0/0/0/-11/0]soft");
        BendableScore currentScore = BendableScore.parseScore("[0]hard/[0/0/0/-10/0]soft");

        SolverRunner.TerminationReason reason = solverRunner.determineTerminationReason(
                2,
                currentScore,
                previousScore,
                1_001L,
                1_000L);

        assertEquals(SolverRunner.TerminationReason.DEADLINE_REACHED, reason);
    }

    @Test
    void maxIterationsStopsSolverWhenConfigured() {
        SolverRunner solverRunner = new SolverRunner();
        solverRunner.minIterations = 2;
        solverRunner.maxIterations = 3;

        BendableScore previousScore = BendableScore.parseScore("[0]hard/[0/0/0/-11/0]soft");
        BendableScore currentScore = BendableScore.parseScore("[0]hard/[0/0/0/-10/0]soft");

        SolverRunner.TerminationReason reason = solverRunner.determineTerminationReason(
                3,
                currentScore,
                previousScore,
                900L,
                1_000L);

        assertEquals(SolverRunner.TerminationReason.MAX_ITERATIONS_REACHED, reason);
    }
}
