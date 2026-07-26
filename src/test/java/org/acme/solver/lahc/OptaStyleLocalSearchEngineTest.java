package org.acme.solver.lahc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.EmployeeScheduleBuilder;
import org.acme.solver.adapter.PlanningProblemMapper;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.core.TerminationReason;
import org.acme.solver.initial.InitialSolutionBuilder;
import org.acme.solver.move.ReassignMove;
import org.acme.solver.move.SwapMove;
import org.acme.solver.score.FullScoreCalculator;
import org.acme.test.JsonLoader;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class OptaStyleLocalSearchEngineTest {

    private final FullScoreCalculator full = new FullScoreCalculator();

    @Test
    void 거절후_같은_step에서_다음_move를_scan하여_첫_수락_move만_commit한다() {
        PlanningProblem problem = twoShiftSameDayProblem();
        RosterSolution initial = new InitialSolutionBuilder(full).build(problem).solution();
        LahcMoveSelector selector = new LahcMoveSelector() {
            private int calls;

            @Override
            public Optional<org.acme.solver.move.Move> select(
                    PlanningProblem ignored, org.acme.solver.move.SearchState state,
                    java.util.SplittableRandom random) {
                calls++;
                return calls == 1
                        ? Optional.of(ReassignMove.create(
                                problem, state, 0, state.employeeIndex(1)))
                        : Optional.of(SwapMove.create(problem, state, 0, 1));
            }
        };
        OptaStyleLocalSearchEngine engine = new OptaStyleLocalSearchEngine(full, selector, 400);

        SolveResult<RosterSolution> result = engine.solve(
                problem,
                SolveOptions.builder().maxEvaluations(2).warmStart(initial).randomSeed(42L).build(),
                ignored -> {
                });

        OptaStyleLocalSearchMetrics metrics = assertInstanceOf(
                OptaStyleLocalSearchMetrics.class, result.metrics());
        assertEquals(TerminationReason.MAX_EVALUATIONS_REACHED, result.terminationReason());
        assertEquals(2L, result.evaluationCount());
        assertEquals(1L, result.iterations());
        assertEquals(1L, metrics.rejectedCandidates());
        assertEquals(0L, metrics.cancelledCandidates());
        assertEquals(1L, metrics.changeCandidates());
        assertEquals(1L, metrics.swapCandidates());
        assertEquals(1L, metrics.acceptedSteps());
        assertEquals(0, result.score().hardScore(), result.score()::toString);
        assertEquals(full.calculateScore(problem, result.bestSolution()), result.score());
    }

    @Test
    void 같은_seed와_평가예산은_assignment_score_trace를_재현한다() {
        PlanningProblem problem = deterministicProblem();
        SolveOptions options = SolveOptions.builder()
                .maxEvaluations(100)
                .randomSeed(20260723L)
                .build();

        SolveResult<RosterSolution> first = new OptaStyleLocalSearchEngine().solve(problem, options, ignored -> {
        });
        SolveResult<RosterSolution> second = new OptaStyleLocalSearchEngine().solve(problem, options, ignored -> {
        });

        assertArrayEquals(first.bestSolution().employeeIndexByShift(), second.bestSolution().employeeIndexByShift());
        assertEquals(first.score(), second.score());
        assertEquals(first.evaluationCount(), second.evaluationCount());
        assertEquals(first.metrics(), second.metrics());
        assertTrue(first.score().isFeasible(), first.score()::toString);
    }

    @Test
    void preceptor_초기해가_infeasible여도_관계_aware_bootstrap_뒤에는_hard_0으로_탐색한다()
            throws Exception {
        PlanningProblem problem = preceptorProblem();
        RosterSolution rawInitial = new InitialSolutionBuilder(full).build(problem).solution();
        assertTrue(!rawInitial.score().isFeasible(),
                "이 fixture는 Opta-style lane의 feasibility bootstrap 경로를 재현해야 합니다.");

        SolveResult<RosterSolution> result = new OptaStyleLocalSearchEngine().solve(
                problem,
                SolveOptions.builder().maxEvaluations(1L).randomSeed(42L).build(),
                ignored -> {
                });
        OptaStyleLocalSearchMetrics metrics = assertInstanceOf(
                OptaStyleLocalSearchMetrics.class, result.metrics());

        assertEquals(TerminationReason.MAX_EVALUATIONS_REACHED, result.terminationReason());
        assertEquals(1L, result.evaluationCount());
        assertEquals(OptaStyleLocalSearchEngine.DEFAULT_INITIAL_FEASIBILITY_EVALUATIONS,
                metrics.initialFeasibilityEvaluations());
        assertEquals(0, result.score().hardScore(), result.score()::toString);
        assertEquals(full.calculateScore(problem, result.bestSolution()), result.score());
    }

    private static PlanningProblem preceptorProblem() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PlanningRequest request = objectMapper.readValue(
                JsonLoader.loadAsString("/json/preceptor.json"), PlanningRequest.class);
        return new PlanningProblemMapper().toPlanningProblem(new EmployeeScheduleBuilder().build(request));
    }

    private static PlanningProblem twoShiftSameDayProblem() {
        List<PlanningProblem.EmployeeData> employees = employees(2);
        LocalDate date = LocalDate.of(2026, 1, 1);
        return new PlanningProblem(
                window(),
                employees,
                List.of(shift(1L, date, -1), shift(2L, date, -1)),
                List.of());
    }

    private static PlanningProblem deterministicProblem() {
        List<PlanningProblem.ShiftData> shifts = new ArrayList<>();
        long id = 1L;
        for (int day = 0; day < 7; day++) {
            LocalDate date = LocalDate.of(2026, 1, 1).plusDays(day);
            shifts.add(shift(id++, date, -1));
            shifts.add(shift(id++, date, -1));
        }
        return new PlanningProblem(window(), employees(4), shifts, List.of());
    }

    private static List<PlanningProblem.EmployeeData> employees(int count) {
        List<PlanningProblem.EmployeeData> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            result.add(new PlanningProblem.EmployeeData(
                    "e" + index, "e" + index, Set.of("ALL"), Set.of("D"),
                    0, 0, 0, 1, null));
        }
        return result;
    }

    private static PlanningProblem.ShiftData shift(long id, LocalDate date, int employeeIndex) {
        LocalDateTime start = date.atTime(8, 0);
        return new PlanningProblem.ShiftData(
                id, "s" + id, "D", start, start.plusHours(8), date,
                "ward", "ALL", false, employeeIndex, 0, 0);
    }

    private static PlanningProblem.ScheduleWindow window() {
        return new PlanningProblem.ScheduleWindow(
                "tenant", "opta-style", 0, 31,
                LocalDate.of(2026, 1, 1), LocalDate.of(2025, 12, 31));
    }
}
