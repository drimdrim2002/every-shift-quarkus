package org.acme.solver.lahc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveMetrics;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.core.SolverEngine;
import org.acme.solver.core.TerminationReason;
import org.acme.solver.score.FullScoreCalculator;
import org.junit.jupiter.api.Test;

class SequentialHybridSolverEngineTest {

    @Test
    void 단계는_검증된_snapshot으로_연결되고_평가예산과_listener는_전역으로_집계된다() {
        PlanningProblem problem = fairnessProblem();
        FullScoreCalculator full = new FullScoreCalculator();
        RosterSolution root = solution(problem, full, 0, 0, 0);
        AtomicReference<RosterSolution> secondInput = new AtomicReference<>();
        SolverEngine opta = (ignored, options, listener) -> {
            RosterSolution improved = solution(problem, full, 1, 0, 0);
            listener.onBestSolution(improved);
            return new SolveResult<>(improved, improved.score(), TerminationReason.MAX_EVALUATIONS_REACHED,
                    1L, options.maxEvaluations(), 0L, options.randomSeed(), SolveMetrics.empty());
        };
        SolverEngine alns = (ignored, options, listener) -> {
            secondInput.set(options.warmStart().orElseThrow());
            return new SolveResult<>(secondInput.get(), secondInput.get().score(),
                    TerminationReason.MAX_EVALUATIONS_REACHED, 1L, options.maxEvaluations(), 0L,
                    options.randomSeed(), SolveMetrics.empty());
        };
        List<RosterScore> callbacks = new ArrayList<>();
        SequentialHybridSolverEngine engine = new SequentialHybridSolverEngine(
                SequentialHybridSolverEngine.Order.OPTA_STYLE_THEN_ALNS, opta, alns, full);

        SolveResult<RosterSolution> result = engine.solve(problem,
                SolveOptions.builder().maxEvaluations(5).warmStart(root).randomSeed(401L).build(),
                solution -> callbacks.add(solution.score()));

        SequentialHybridMetrics metrics = (SequentialHybridMetrics) result.metrics();
        assertEquals(-5, result.score().softScore(1)); // fairness
        assertEquals(result.score(), secondInput.get().score());
        assertEquals(5L, result.evaluationCount());
        assertEquals(3L, metrics.stages().getFirst().evaluationBudget());
        assertEquals(2L, metrics.stages().getLast().evaluationBudget());
        assertEquals(0L, metrics.rollbackFailureCount());
        assertEquals(List.of(RosterScore.of(0, 0, -9, 0, 0), RosterScore.of(0, 0, -5, 0, 0)), callbacks);
        assertTrue(metrics.stages().getLast().inputScore().compareTo(metrics.stages().getFirst().outputScore()) == 0);
    }

    @Test
    void 동일_seed와_고정평가예산은_hybrid_trace를_재현한다() {
        PlanningProblem problem = fairnessProblem();
        FullScoreCalculator full = new FullScoreCalculator();
        RosterSolution root = solution(problem, full, 0, 0, 0);
        SolveOptions options = SolveOptions.builder().maxEvaluations(4).warmStart(root).randomSeed(501L).build();

        SolveResult<RosterSolution> first = new SequentialHybridSolverEngine(
                SequentialHybridSolverEngine.Order.OPTA_STYLE_THEN_ALNS).solve(problem, options, ignored -> { });
        SolveResult<RosterSolution> second = new SequentialHybridSolverEngine(
                SequentialHybridSolverEngine.Order.OPTA_STYLE_THEN_ALNS).solve(problem, options, ignored -> { });

        assertArrayEquals(first.bestSolution().employeeIndexByShift(), second.bestSolution().employeeIndexByShift());
        assertEquals(first.score(), second.score());
        assertEquals(first.evaluationCount(), second.evaluationCount());
        SequentialHybridMetrics firstMetrics = (SequentialHybridMetrics) first.metrics();
        SequentialHybridMetrics secondMetrics = (SequentialHybridMetrics) second.metrics();
        assertEquals(firstMetrics.order(), secondMetrics.order());
        assertEquals(firstMetrics.rootSeed(), secondMetrics.rootSeed());
        assertEquals(firstMetrics.totalEvaluationCount(), secondMetrics.totalEvaluationCount());
        assertEquals(firstMetrics.scoreMismatchFailures(), secondMetrics.scoreMismatchFailures());
        assertEquals(firstMetrics.stateCorruptionFailures(), secondMetrics.stateCorruptionFailures());
        assertEquals(firstMetrics.terminationReason(), secondMetrics.terminationReason());
        assertEquals(firstMetrics.stages().stream().map(stage -> List.of(
                stage.engineId(), stage.derivedSeed(), stage.evaluationBudget(), stage.evaluationCount(),
                stage.inputScore(), stage.outputScore(), stage.terminationReason())).toList(),
                secondMetrics.stages().stream().map(stage -> List.of(
                        stage.engineId(), stage.derivedSeed(), stage.evaluationBudget(), stage.evaluationCount(),
                        stage.inputScore(), stage.outputScore(), stage.terminationReason())).toList());
    }

    private static PlanningProblem fairnessProblem() {
        LocalDate date = LocalDate.of(2026, 4, 1);
        List<PlanningProblem.EmployeeData> employees = List.of(employee("e0"), employee("e1"));
        List<PlanningProblem.ShiftData> shifts = List.of(
                shift(1L, date), shift(2L, date.plusDays(1)), shift(3L, date.plusDays(2)));
        return new PlanningProblem(new PlanningProblem.ScheduleWindow(
                "tenant", "hybrid", 0, 31, date, date.minusDays(1)), employees, shifts, List.of());
    }

    private static RosterSolution solution(PlanningProblem problem, FullScoreCalculator full, int... assignment) {
        RosterSolution provisional = new RosterSolution(problem.employeeCount(), assignment, RosterScore.of(0, 0, 0, 0, 0));
        return new RosterSolution(problem.employeeCount(), assignment, full.calculateScore(problem, provisional));
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
