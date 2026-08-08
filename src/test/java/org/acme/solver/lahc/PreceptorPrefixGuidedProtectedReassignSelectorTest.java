package org.acme.solver.lahc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.move.SearchState;
import org.acme.solver.score.FullScoreCalculator;
import org.junit.jupiter.api.Test;

class PreceptorPrefixGuidedProtectedReassignSelectorTest {

    @Test
    void full_score_breakdown의_soft1_shift를_다른_reassign보다_먼저_반환한다() {
        PlanningProblem problem = fixture();
        RosterSolution solution = scored(problem, 0, 0);
        PreceptorPrefixGuidedProtectedReassignSelector selector =
                new PreceptorPrefixGuidedProtectedReassignSelector(new FullScoreCalculator());

        List<PreceptorPrefixGuidedProtectedReassignSelector.Candidate> candidates =
                selector.candidates(problem, new SearchState(problem, solution));

        // soft[0]=undesired → shift0 priority 0; soft[1]=fairness 기여 가능 → shift1 priority 1
        assertEquals(List.of(
                new PreceptorPrefixGuidedProtectedReassignSelector.Candidate(0, 1, 0),
                new PreceptorPrefixGuidedProtectedReassignSelector.Candidate(1, 1, 1)), candidates);
        assertEquals(new PreceptorPrefixGuidedProtectedReassignSelector.Metrics(1L, 2L, 1L, 1L),
                selector.metrics());
    }

    @Test
    void 같은_state는_동일한_안정된_후보열을_재현한다() {
        PlanningProblem problem = fixture();
        RosterSolution solution = scored(problem, 0, 0);
        PreceptorPrefixGuidedProtectedReassignSelector first =
                new PreceptorPrefixGuidedProtectedReassignSelector(new FullScoreCalculator());
        PreceptorPrefixGuidedProtectedReassignSelector second =
                new PreceptorPrefixGuidedProtectedReassignSelector(new FullScoreCalculator());

        assertEquals(first.candidates(problem, new SearchState(problem, solution)),
                second.candidates(problem, new SearchState(problem, solution)));
    }

    private static RosterSolution scored(PlanningProblem problem, int... assignments) {
        FullScoreCalculator full = new FullScoreCalculator();
        RosterSolution provisional = new RosterSolution(problem.employeeCount(), assignments,
                RosterScore.of(0, 0, 0, 0, 0));
        return new RosterSolution(problem.employeeCount(), assignments,
                full.calculateScore(problem, provisional));
    }

    private static PlanningProblem fixture() {
        LocalDate first = LocalDate.of(2026, 4, 1);
        return new PlanningProblem(new PlanningProblem.ScheduleWindow(
                "tenant", "preceptor-prefix-selector", 0, 31, first, first.minusDays(1)),
                List.of(employee("e0"), employee("e1")),
                List.of(shift(1L, first), shift(2L, first.plusDays(1))),
                List.of(new PlanningProblem.AvailabilityData(
                        null, 0, first, PlanningProblem.AvailabilityKind.UNDESIRED)));
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
