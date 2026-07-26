package org.acme.solver.lahc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.move.Move;
import org.acme.solver.move.MoveTransaction;
import org.acme.solver.move.SearchState;
import org.acme.solver.score.FullScoreCalculator;
import org.acme.solver.score.IncrementalScoreCalculator;
import org.junit.jupiter.api.Test;

class FairnessHotspotGuidedProtectedReassignSelectorTest {

    @Test
    void hotspot_제곱_delta순과_안정_tie_break로_후보를_열거한다() {
        PlanningProblem problem = fixture();
        RosterSolution solution = solution(problem, new int[] { 0, 0, 0 });
        SearchState state = new SearchState(problem, solution);
        FairnessHotspotGuidedProtectedReassignSelector selector =
                new FairnessHotspotGuidedProtectedReassignSelector(3);

        Move first = selector.select(problem, state, new SplittableRandom(99L)).orElseThrow();
        Move second = selector.select(problem, state, new SplittableRandom(88L)).orElseThrow();
        Move third = selector.select(problem, state, new SplittableRandom(77L)).orElseThrow();

        assertEquals("REASSIGN", first.moveType());
        assertEquals(0, first.changes().getFirst().shiftIndex());
        assertEquals(1, first.changes().getFirst().newEmployeeIndex());
        assertEquals(1, second.changes().getFirst().shiftIndex());
        assertEquals(2, third.changes().getFirst().shiftIndex());
        assertTrue(selector.select(problem, state, new SplittableRandom(66L)).isEmpty());
        assertEquals(new FairnessSelectorMetrics(1L, 3L, 3L, 3L, 3L), selector.metrics());
    }

    @Test
    void 후보는_full_score_검증_뒤에만_보호된_개선으로_판정되고_rollback된다() {
        PlanningProblem problem = fixture();
        FullScoreCalculator full = new FullScoreCalculator();
        RosterSolution incumbent = solution(problem, new int[] { 0, 0, 0 });
        SearchState state = new SearchState(problem, incumbent);
        FairnessHotspotGuidedProtectedReassignSelector selector =
                new FairnessHotspotGuidedProtectedReassignSelector();
        Move move = selector.select(problem, state, new SplittableRandom(1L)).orElseThrow();
        IncrementalScoreCalculator incremental = new IncrementalScoreCalculator(problem, incumbent, full);

        try (MoveTransaction transaction = MoveTransaction.open(state, incremental)) {
            transaction.apply(move);
            RosterScore candidate = transaction.verifyCandidate().score();
            assertTrue(FairnessRestrictedLocalSearchEngine.isAllowedFairnessImprovement(incumbent.score(), candidate));
            transaction.rollback();
        }
        assertArrayEquals(incumbent.employeeIndexByShift(), state.assignments());
        assertEquals(incumbent.score(), state.score());
    }

    @Test
    void 동일_state는_seed와_무관하게_동일한_후보열을_반환한다() {
        PlanningProblem problem = fixture();
        RosterSolution solution = solution(problem, new int[] { 0, 0, 0 });
        List<List<?>> first = selectAll(problem, solution, 5L);
        List<List<?>> second = selectAll(problem, solution, 909L);
        assertEquals(first, second);
    }

    private static List<List<?>> selectAll(PlanningProblem problem, RosterSolution solution, long seed) {
        SearchState state = new SearchState(problem, solution);
        FairnessHotspotGuidedProtectedReassignSelector selector =
                new FairnessHotspotGuidedProtectedReassignSelector(3);
        SplittableRandom random = new SplittableRandom(seed);
        return List.of(
                selector.select(problem, state, random).map(Move::changes).orElse(List.of()),
                selector.select(problem, state, random).map(Move::changes).orElse(List.of()),
                selector.select(problem, state, random).map(Move::changes).orElse(List.of()));
    }

    private static RosterSolution solution(PlanningProblem problem, int[] assignments) {
        FullScoreCalculator full = new FullScoreCalculator();
        RosterSolution provisional = new RosterSolution(problem.employeeCount(), assignments, RosterScore.of(0, 0, 0, 0, 0));
        return new RosterSolution(problem.employeeCount(), assignments, full.calculateScore(problem, provisional));
    }

    private static PlanningProblem fixture() {
        LocalDate date = LocalDate.of(2026, 4, 1);
        List<PlanningProblem.EmployeeData> employees = List.of(employee("e0"), employee("e1"));
        List<PlanningProblem.ShiftData> shifts = List.of(
                shift(1L, date), shift(2L, date.plusDays(1)), shift(3L, date.plusDays(2)));
        return new PlanningProblem(new PlanningProblem.ScheduleWindow(
                "tenant", "fairness-hotspot-guided", 0, 31, date, date.minusDays(1)), employees, shifts, List.of());
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
