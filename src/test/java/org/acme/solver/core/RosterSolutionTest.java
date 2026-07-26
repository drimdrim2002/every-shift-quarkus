package org.acme.solver.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RosterSolutionTest {

    @Test
    void completeAssignment만_허용하고_배열을_방어적으로_복사한다() {
        int[] assignments = { 1, 0 };
        RosterSolution solution = new RosterSolution(
                2,
                assignments,
                RosterScore.of(0, 1, 2, 3, 4));

        assignments[0] = 0;
        int[] snapshot = solution.employeeIndexByShift();
        snapshot[1] = 1;

        assertArrayEquals(new int[] { 1, 0 }, solution.employeeIndexByShift());
        assertEquals(1, solution.employeeIndex(0));
    }

    @Test
    void 미배정이나_범위_밖_직원_index를_거절한다() {
        RosterScore score = RosterScore.of(0, 0, 0, 0, 0);

        assertThrows(IllegalArgumentException.class, () -> new RosterSolution(2, new int[] { -1 }, score));
        assertThrows(IllegalArgumentException.class, () -> new RosterSolution(2, new int[] { 2 }, score));
    }
}
