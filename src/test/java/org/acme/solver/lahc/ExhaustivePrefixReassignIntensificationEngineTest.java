package org.acme.solver.lahc;

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
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.score.FullScoreCalculator;
import org.junit.jupiter.api.Test;

class ExhaustivePrefixReassignIntensificationEngineTest {

    private final FullScoreCalculator full = new FullScoreCalculator();

    @Test
    void 모든_reassign을_검증해_사전식_개선을_commit하고_final_score를_재검증한다() {
        PlanningProblem problem = unevenDayShiftProblem();
        RosterSolution warm = scored(problem, 0, 0, 0);

        SolveResult<RosterSolution> result = new ExhaustivePrefixReassignIntensificationEngine(6).solve(
                problem,
                SolveOptions.builder().warmStart(warm).maxEvaluations(12).randomSeed(1401L).build(),
                ignored -> {
                });
        ExhaustivePrefixReassignIntensificationMetrics metrics =
                (ExhaustivePrefixReassignIntensificationMetrics) result.metrics();

        assertTrue(result.score().compareTo(warm.score()) > 0, result::toString);
        assertTrue(metrics.acceptedCandidates() > 0L, metrics::toString);
        assertEquals(metrics.evaluatedCandidates(),
                metrics.acceptedCandidates() + metrics.rejectedCandidates());
        assertTrue(metrics.fullVerificationCount() > metrics.evaluatedCandidates());
        assertEquals(full.calculateScore(problem, result.bestSolution()), result.score());
        assertEquals(0L, metrics.scoreMismatchFailures());
        assertEquals(0L, metrics.stateCorruptionFailures());
    }

    @Test
    void 동일_seed와_고정_예산은_assignment_score_rollback을_결정론적으로_재현한다() {
        PlanningProblem problem = unevenDayShiftProblem();
        RosterSolution warm = scored(problem, 0, 0, 0);
        SolveOptions options =
                SolveOptions.builder().warmStart(warm).maxEvaluations(12).randomSeed(1402L).build();

        SolveResult<RosterSolution> first =
                new ExhaustivePrefixReassignIntensificationEngine(6)
                        .solve(problem, options, ignored -> {
                        });
        SolveResult<RosterSolution> second =
                new ExhaustivePrefixReassignIntensificationEngine(6)
                        .solve(problem, options, ignored -> {
                        });

        assertArrayEquals(first.bestSolution().employeeIndexByShift(),
                second.bestSolution().employeeIndexByShift());
        assertEquals(first.score(), second.score());
        ExhaustivePrefixReassignIntensificationMetrics metrics =
                (ExhaustivePrefixReassignIntensificationMetrics) first.metrics();
        ExhaustivePrefixReassignIntensificationMetrics repeated =
                (ExhaustivePrefixReassignIntensificationMetrics) second.metrics();
        assertEquals(metrics.evaluationCount(), repeated.evaluationCount());
        assertEquals(metrics.acceptedCandidates(), repeated.acceptedCandidates());
        assertEquals(metrics.rejectedCandidates(), repeated.rejectedCandidates());
        assertEquals(metrics.selectorMetrics(), repeated.selectorMetrics());
        assertEquals(0L, metrics.rollbackFailureCount());
        assertEquals(0L, metrics.scoreMismatchFailures());
        assertTrue(RosterScore.of(0, 0, 1, -100, 0)
                .compareTo(RosterScore.of(0, 0, 0, 100, 0)) > 0);
    }

    private RosterSolution scored(PlanningProblem problem, int... assignments) {
        RosterSolution provisional = new RosterSolution(
                problem.employeeCount(), assignments, RosterScore.of(0, 0, 0, 0, 0));
        return new RosterSolution(problem.employeeCount(), assignments,
                full.calculateScore(problem, provisional));
    }

    private static PlanningProblem unevenDayShiftProblem() {
        LocalDate first = LocalDate.of(2026, 4, 1);
        return new PlanningProblem(new PlanningProblem.ScheduleWindow(
                "tenant", "prefix-reassign", 0, 31, first, first.minusDays(1)),
                List.of(employee("e0"), employee("e1")),
                List.of(
                        shift(1L, first),
                        shift(2L, first.plusDays(1)),
                        shift(3L, first.plusDays(2))),
                List.of());
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
