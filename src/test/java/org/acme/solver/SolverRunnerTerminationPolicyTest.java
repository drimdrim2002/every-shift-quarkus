package org.acme.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.acme.solver.core.RosterScore;
import org.acme.solver.core.TerminationReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SolverRunnerTerminationPolicyTest {

    private SolverRunner solverRunner;

    @BeforeEach
    void setUp() {
        solverRunner = new SolverRunner();
        solverRunner.maxIterations = 6;
        solverRunner.minIterations = 1; // 레거시 no-op
    }

    @Test
    void pass1_hardFeasible_stopsWithConverged() {
        RosterScore bestAfter = RosterScore.of(0, -10, 0, 0, 0);

        TerminationReason reason = solverRunner.determineTerminationReason(
                1,
                6,
                bestAfter,
                null,
                900L,
                1_000L);

        assertEquals(TerminationReason.CONVERGED, reason);
    }

    @Test
    void pass1_hardViolated_continues() {
        RosterScore bestAfter = RosterScore.of(-5, -10, 0, 0, 0);

        TerminationReason reason = solverRunner.determineTerminationReason(
                1,
                6,
                bestAfter,
                null,
                900L,
                1_000L);

        assertEquals(TerminationReason.CONTINUE, reason);
    }

    @Test
    void pass2_hardFeasible_sameScore_stops() {
        RosterScore score = RosterScore.of(0, -10, 0, 0, 0);

        TerminationReason reason = solverRunner.determineTerminationReason(
                2,
                6,
                score,
                score,
                900L,
                1_000L);

        assertEquals(TerminationReason.CONVERGED, reason);
    }

    @Test
    void pass2_hardFeasible_strictImprovement_continues() {
        RosterScore before = RosterScore.of(0, -20, 0, 0, 0);
        RosterScore after = RosterScore.of(0, -10, 0, 0, 0);

        TerminationReason reason = solverRunner.determineTerminationReason(
                2,
                6,
                after,
                before,
                900L,
                1_000L);

        assertEquals(TerminationReason.CONTINUE, reason);
    }

    @Test
    void pass2_hardViolated_sameScore_continues() {
        RosterScore score = RosterScore.of(-3, -10, 0, 0, 0);

        TerminationReason reason = solverRunner.determineTerminationReason(
                2,
                6,
                score,
                score,
                900L,
                1_000L);

        assertEquals(TerminationReason.CONTINUE, reason);
    }

    @Test
    void pass6_anyScore_maxIterationsReached() {
        RosterScore before = RosterScore.of(-1, -100, 0, 0, 0);
        RosterScore after = RosterScore.of(0, 0, 0, 0, 0);

        TerminationReason reason = solverRunner.determineTerminationReason(
                6,
                6,
                after,
                before,
                900L,
                1_000L);

        assertEquals(TerminationReason.MAX_ITERATIONS_REACHED, reason);
    }

    @Test
    void deadlineStopsSolverEvenWhenHardViolated() {
        RosterScore before = RosterScore.of(-5, 0, 0, 0, 0);
        RosterScore after = RosterScore.of(-4, 0, 0, 0, 0);

        TerminationReason reason = solverRunner.determineTerminationReason(
                2,
                6,
                after,
                before,
                1_001L,
                1_000L);

        assertEquals(TerminationReason.DEADLINE_REACHED, reason);
    }

    @Test
    void deadlineStopsSolverEvenWhenNotConverged_legacyCompat() {
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
    void maxIterationsStopsSolverWhenConfigured_legacyCompat() {
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

    @Test
    void equalScoreIsNotStrictImprovement() {
        RosterScore before = RosterScore.of(0, -5, 1, 0, 0);
        RosterScore after = RosterScore.of(0, -5, 1, 0, 0);

        TerminationReason reason = solverRunner.determineTerminationReason(
                3,
                6,
                after,
                before,
                900L,
                1_000L);

        assertEquals(TerminationReason.CONVERGED, reason);
    }
}
