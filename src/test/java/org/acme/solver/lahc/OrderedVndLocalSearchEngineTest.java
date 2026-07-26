package org.acme.solver.lahc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.core.TerminationReason;
import org.acme.solver.score.FullScoreCalculator;
import org.junit.jupiter.api.Test;

class OrderedVndLocalSearchEngineTest {

    private final FullScoreCalculator full = new FullScoreCalculator();

    @Test
    void reassign_개선_뒤에는_첫_neighborhood부터_재시작하고_모든_후보를_full_score로_검증한다() {
        PlanningProblem problem = unevenDayShiftProblem();
        RosterSolution warm = scored(problem, 0, 0, 0);

        SolveResult<RosterSolution> result = new OrderedVndLocalSearchEngine(32).solve(problem,
                SolveOptions.builder().warmStart(warm).maxEvaluations(64).randomSeed(901L).build(), ignored -> { });

        OrderedVndLocalSearchMetrics metrics = (OrderedVndLocalSearchMetrics) result.metrics();
        assertTrue(result.score().compareTo(warm.score()) > 0);
        assertTrue(metrics.acceptedReassignCandidates() > 0L);
        assertTrue(metrics.fullVerificationCount() >= metrics.evaluationCount());
        assertEquals(full.calculateScore(problem, result.bestSolution()), result.score());
        assertEquals(0L, metrics.scoreMismatchFailures());
        assertEquals(0L, metrics.stateCorruptionFailures());
    }

    @Test
    void 개선이_없는_후보는_rollback되고_warm_start를_보존한다() {
        PlanningProblem problem = skillSensitiveProblem();
        RosterSolution warm = scored(problem, 0);

        SolveResult<RosterSolution> result = new OrderedVndLocalSearchEngine(8).solve(problem,
                SolveOptions.builder().warmStart(warm).maxEvaluations(8).randomSeed(902L).build(), ignored -> { });

        OrderedVndLocalSearchMetrics metrics = (OrderedVndLocalSearchMetrics) result.metrics();
        assertEquals(TerminationReason.CONVERGED, result.terminationReason());
        assertArrayEquals(warm.employeeIndexByShift(), result.bestSolution().employeeIndexByShift());
        assertEquals(warm.score(), result.score());
        assertEquals(1L, metrics.rollbackAttemptCount());
        assertEquals(0L, metrics.rollbackFailureCount());
        assertEquals(0L, metrics.scoreMismatchFailures());
    }

    @Test
    void 동일_seed와_고정_예산에서_결정론적이며_사전식_상위_목적식을_우선한다() {
        PlanningProblem problem = unevenDayShiftProblem();
        RosterSolution warm = scored(problem, 0, 0, 0);
        SolveOptions options = SolveOptions.builder().warmStart(warm).maxEvaluations(64).randomSeed(903L).build();

        SolveResult<RosterSolution> first = new OrderedVndLocalSearchEngine(32).solve(problem, options, ignored -> { });
        SolveResult<RosterSolution> second = new OrderedVndLocalSearchEngine(32).solve(problem, options, ignored -> { });

        assertArrayEquals(first.bestSolution().employeeIndexByShift(), second.bestSolution().employeeIndexByShift());
        assertEquals(first.score(), second.score());
        assertEquals(first.evaluationCount(), second.evaluationCount());
        OrderedVndLocalSearchMetrics firstMetrics = (OrderedVndLocalSearchMetrics) first.metrics();
        OrderedVndLocalSearchMetrics secondMetrics = (OrderedVndLocalSearchMetrics) second.metrics();
        assertEquals(firstMetrics.evaluationCount(), secondMetrics.evaluationCount());
        assertEquals(firstMetrics.acceptedReassignCandidates(), secondMetrics.acceptedReassignCandidates());
        assertEquals(firstMetrics.acceptedSwapCandidates(), secondMetrics.acceptedSwapCandidates());
        assertEquals(firstMetrics.bestImprovements().stream()
                .map(item -> List.of(item.evaluation(), item.score(), item.neighborhood())).toList(),
                secondMetrics.bestImprovements().stream()
                        .map(item -> List.of(item.evaluation(), item.score(), item.neighborhood())).toList());
        RosterScore higherSoft1ButLowerSoft2 = RosterScore.of(0, 0, 1, -100, 0);
        RosterScore lowerSoft1ButHigherSoft2 = RosterScore.of(0, 0, 0, 100, 0);
        assertTrue(higherSoft1ButLowerSoft2.compareTo(lowerSoft1ButHigherSoft2) > 0);
        assertFalse(higherSoft1ButLowerSoft2.compareTo(lowerSoft1ButHigherSoft2) <= 0);
    }

    private RosterSolution scored(PlanningProblem problem, int... assignments) {
        RosterSolution provisional = new RosterSolution(problem.employeeCount(), assignments, RosterScore.of(0, 0, 0, 0, 0));
        return new RosterSolution(problem.employeeCount(), assignments, full.calculateScore(problem, provisional));
    }

    private static PlanningProblem unevenDayShiftProblem() {
        LocalDate first = LocalDate.of(2026, 4, 1);
        return new PlanningProblem(new PlanningProblem.ScheduleWindow(
                "tenant", "ordered-vnd", 0, 31, first, first.minusDays(1)),
                List.of(employee("e0", Set.of("ALL")), employee("e1", Set.of("ALL"))),
                List.of(shift(1L, first), shift(2L, first.plusDays(1)), shift(3L, first.plusDays(2))), List.of());
    }

    private static PlanningProblem skillSensitiveProblem() {
        LocalDate first = LocalDate.of(2026, 4, 1);
        return new PlanningProblem(new PlanningProblem.ScheduleWindow(
                "tenant", "ordered-vnd-skill", 0, 31, first, first.minusDays(1)),
                List.of(employee("qualified", Set.of("ALL")), employee("unqualified", Set.of("OTHER"))),
                List.of(shift(1L, first)), List.of());
    }

    private static PlanningProblem.EmployeeData employee(String id, Set<String> skills) {
        return new PlanningProblem.EmployeeData(id, id, skills, Set.of("D"), 0, 0, 0, 1, null);
    }

    private static PlanningProblem.ShiftData shift(long id, LocalDate date) {
        LocalDateTime start = date.atTime(8, 0);
        return new PlanningProblem.ShiftData(id, "s" + id, "D", start, start.plusHours(8), date,
                "ward", "ALL", false, 0, 0, 0);
    }
}
