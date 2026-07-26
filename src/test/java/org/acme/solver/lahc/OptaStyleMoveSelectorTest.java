package org.acme.solver.lahc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.move.Move;
import org.acme.solver.move.SearchState;
import org.junit.jupiter.api.Test;

class OptaStyleMoveSelectorTest {

    @Test
    void 같은_seed는_change_swap_trace를_재현하고_pinned를_제외한다() {
        PlanningProblem problem = problem();
        SearchState firstState = state(problem);
        SearchState secondState = state(problem);
        OptaStyleMoveSelector selector = new OptaStyleMoveSelector();
        SplittableRandom firstRandom = new SplittableRandom(20260723L);
        SplittableRandom secondRandom = new SplittableRandom(20260723L);

        for (int index = 0; index < 100; index++) {
            Move first = selector.select(problem, firstState, firstRandom).orElseThrow();
            Move second = selector.select(problem, secondState, secondRandom).orElseThrow();

            assertEquals(signature(first), signature(second));
            assertTrue(first.moveType().equals("REASSIGN") || first.moveType().equals("SWAP"));
            assertTrue(first.changes().stream().noneMatch(change -> change.shiftIndex() == 0));
        }
    }

    @Test
    void selector는_skill이나_shift_code를_사전필터하지_않고_value_range를_후보로_쓴다() {
        PlanningProblem problem = problem();
        SearchState state = state(problem);
        OptaStyleMoveSelector selector = new OptaStyleMoveSelector();
        SplittableRandom random = new SplittableRandom(7L);
        boolean observedOtherSkill = false;

        for (int index = 0; index < 200; index++) {
            Move move = selector.select(problem, state, random).orElseThrow();
            observedOtherSkill |= move.changes().stream().anyMatch(change -> change.newEmployeeIndex() == 2);
        }

        assertTrue(observedOtherSkill, "제약 위반 value도 exact score가 거절하도록 candidate stream에 있어야 합니다.");
    }

    private static String signature(Move move) {
        return move.moveType() + move.changes();
    }

    private static SearchState state(PlanningProblem problem) {
        int[] assignments = problem.initialEmployeeIndexByShift();
        return new SearchState(problem, new RosterSolution(
                problem.employeeCount(), assignments, RosterScore.of(0, 0, 0, 0, 0)));
    }

    private static PlanningProblem problem() {
        List<PlanningProblem.EmployeeData> employees = List.of(
                employee("e0", Set.of("RN")),
                employee("e1", Set.of("RN")),
                employee("e2", Set.of("OTHER")));
        List<PlanningProblem.ShiftData> shifts = new ArrayList<>();
        shifts.add(shift(0L, 0, true));
        shifts.add(shift(1L, 0, false));
        shifts.add(shift(2L, 1, false));
        return new PlanningProblem(
                new PlanningProblem.ScheduleWindow(
                        "tenant", "opta-style", 0, 10,
                        LocalDate.of(2026, 1, 1), LocalDate.of(2025, 12, 31)),
                employees,
                shifts,
                List.of());
    }

    private static PlanningProblem.EmployeeData employee(String id, Set<String> skills) {
        return new PlanningProblem.EmployeeData(id, id, skills, Set.of("D"), 0, 0, 0, 1, null);
    }

    private static PlanningProblem.ShiftData shift(long id, int employeeIndex, boolean pinned) {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 8, 0).plusDays(id);
        return new PlanningProblem.ShiftData(
                id, "s" + id, "D", start, start.plusHours(8), start.toLocalDate(),
                "ward", "RN", pinned, employeeIndex, 0, 0);
    }
}
