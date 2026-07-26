package org.acme.solver.lahc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.move.ReassignMove;
import org.acme.solver.score.FullScoreCalculator;
import org.junit.jupiter.api.Test;

class FairnessRestrictedLocalSearchEngineTest {

    @Test
    void 상위_목적식을_보존하고_형평성만_개선하는_change를_commit한다() {
        PlanningProblem problem = unevenDayShiftProblem();
        FullScoreCalculator full = new FullScoreCalculator();
        RosterSolution warmStart = new RosterSolution(2, new int[] { 0, 0, 0 },
                full.calculateScore(problem, new RosterSolution(2, new int[] { 0, 0, 0 }, RosterScore.of(0, 0, 0, 0, 0))));
        LahcMoveSelector selector = new LahcMoveSelector() {
            private boolean emitted;

            @Override
            public Optional<org.acme.solver.move.Move> select(
                    PlanningProblem ignored, org.acme.solver.move.SearchState state,
                    java.util.SplittableRandom random) {
                if (emitted) {
                    return Optional.empty();
                }
                emitted = true;
                return Optional.of(ReassignMove.create(problem, state, 0, 1));
            }
        };

        SolveResult<RosterSolution> result = new FairnessRestrictedLocalSearchEngine(full, selector).solve(
                problem, SolveOptions.builder().maxEvaluations(4).warmStart(warmStart).randomSeed(401L).build(),
                ignored -> { });

        FairnessRestrictedLocalSearchMetrics metrics =
                (FairnessRestrictedLocalSearchMetrics) result.metrics();
        assertEquals(0, result.score().hardScore());
        assertEquals(0, result.score().softScore(0));
        assertEquals(0, result.score().softScore(1));
        assertEquals(-5, result.score().softScore(2));
        assertEquals(1L, metrics.acceptedCandidates());
        assertEquals(0L, metrics.stateCorruptionFailures());
        assertEquals(full.calculateScore(problem, result.bestSolution()), result.score());
    }

    @Test
    void higherPriority_악화는_soft2_개선이_있어도_거절한다() {
        RosterScore current = RosterScore.of(0, 0, 0, -9, 0);
        assertTrue(FairnessRestrictedLocalSearchEngine.isAllowedFairnessImprovement(
                current, RosterScore.of(0, 0, 0, -5, -100)));
        assertFalse(FairnessRestrictedLocalSearchEngine.isAllowedFairnessImprovement(
                current, RosterScore.of(-1, 0, 0, -1, 0)));
        assertFalse(FairnessRestrictedLocalSearchEngine.isAllowedFairnessImprovement(
                current, RosterScore.of(0, -1, 0, -1, 0)));
        assertFalse(FairnessRestrictedLocalSearchEngine.isAllowedFairnessImprovement(
                current, RosterScore.of(0, 0, -1, -1, 0)));
    }

    @Test
    void 동일_seed와_평가예산은_제한탐색의_assignment와_선택분포를_재현한다() {
        PlanningProblem problem = unevenDayShiftProblem();
        FullScoreCalculator full = new FullScoreCalculator();
        RosterSolution warmStart = new RosterSolution(2, new int[] { 0, 0, 0 },
                full.calculateScore(problem, new RosterSolution(2, new int[] { 0, 0, 0 }, RosterScore.of(0, 0, 0, 0, 0))));
        SolveOptions options = SolveOptions.builder().maxEvaluations(20).warmStart(warmStart).randomSeed(501L).build();

        SolveResult<RosterSolution> first = new FairnessRestrictedLocalSearchEngine().solve(problem, options, ignored -> { });
        SolveResult<RosterSolution> second = new FairnessRestrictedLocalSearchEngine().solve(problem, options, ignored -> { });

        assertArrayEquals(first.bestSolution().employeeIndexByShift(), second.bestSolution().employeeIndexByShift());
        assertEquals(first.score(), second.score());
        assertEquals(first.metrics(), second.metrics());
    }

    private static PlanningProblem unevenDayShiftProblem() {
        LocalDate first = LocalDate.of(2026, 4, 1);
        List<PlanningProblem.EmployeeData> employees = List.of(employee("e0"), employee("e1"));
        List<PlanningProblem.ShiftData> shifts = List.of(
                shift(1L, first), shift(2L, first.plusDays(1)), shift(3L, first.plusDays(2)));
        return new PlanningProblem(new PlanningProblem.ScheduleWindow(
                "tenant", "fairness-restricted", 0, 31, first, first.minusDays(1)), employees, shifts, List.of());
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
