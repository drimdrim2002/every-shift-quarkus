package org.acme.solver.benchmark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.move.MoveTransaction;
import org.acme.solver.move.SearchState;
import org.acme.solver.score.FullScoreCalculator;
import org.acme.solver.score.IncrementalScoreCalculator;
import org.junit.jupiter.api.Test;

class FairnessProtectedReassignMoveTest {

    @Test
    void 모든_재배정후보를_결정론적으로_열거하고_witness는_rollback된다() {
        PlanningProblem problem = fixture();
        FullScoreCalculator full = new FullScoreCalculator();
        RosterSolution incumbent = solution(problem, full, new int[] { 0, 0, 0 });

        SearchState firstState = new SearchState(problem, incumbent);
        SearchState secondState = new SearchState(problem, incumbent);
        List<FairnessProtectedReassignMove> first = FairnessProtectedReassignMove.enumerate(problem, firstState);
        List<FairnessProtectedReassignMove> second = FairnessProtectedReassignMove.enumerate(problem, secondState);

        assertEquals(3, first.size());
        assertEquals(first.stream().map(FairnessProtectedReassignMove::changes).toList(),
                second.stream().map(FairnessProtectedReassignMove::changes).toList());

        FairnessProtectedReassignMove move = first.getFirst();
        IncrementalScoreCalculator incremental = new IncrementalScoreCalculator(problem, incumbent, full);
        try (MoveTransaction transaction = MoveTransaction.open(firstState, incremental)) {
            transaction.apply(move);
            RosterScore candidate = transaction.verifyCandidate().score();
            assertTrue(FairnessProtectedReassignMove.isProtectedFairnessWitness(incumbent.score(), candidate));
            transaction.rollback();
        }
        assertArrayEquals(incumbent.employeeIndexByShift(), firstState.assignments());
        assertEquals(incumbent.score(), firstState.score());
        assertEquals(0, full.calculateScore(problem, firstState.snapshot()).hardScore());
    }

    private static RosterSolution solution(PlanningProblem problem, FullScoreCalculator full, int[] assignments) {
        RosterSolution provisional = new RosterSolution(problem.employeeCount(), assignments, RosterScore.of(0, 0, 0, 0, 0));
        return new RosterSolution(problem.employeeCount(), assignments, full.calculateScore(problem, provisional));
    }

    private static PlanningProblem fixture() {
        LocalDate date = LocalDate.of(2026, 4, 1);
        List<PlanningProblem.EmployeeData> employees = List.of(
                employee("e0"), employee("e1"));
        List<PlanningProblem.ShiftData> shifts = List.of(
                shift(1L, date), shift(2L, date.plusDays(1)), shift(3L, date.plusDays(2)));
        return new PlanningProblem(new PlanningProblem.ScheduleWindow(
                "tenant", "fairness-protected-reassign", 0, 31, date, date.minusDays(1)), employees, shifts, List.of());
    }

    private static PlanningProblem.EmployeeData employee(String id) {
        return new PlanningProblem.EmployeeData(id, id, Set.of("ALL"), Set.of("D"), 0, 0, 0, 1, null);
    }

    private static PlanningProblem.ShiftData shift(long id, LocalDate date) {
        LocalDateTime start = date.atTime(8, 0);
        return new PlanningProblem.ShiftData(id, "s" + id, "D", start, start.plusHours(8), date,
                "ward", "ALL", false, 0, 0, 0);
    }
}
