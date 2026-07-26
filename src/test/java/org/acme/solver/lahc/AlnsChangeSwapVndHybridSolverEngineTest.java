package org.acme.solver.lahc;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class AlnsChangeSwapVndHybridSolverEngineTest {

    @Test
    void alns_조기종료의_고정평가_잔여분을_vnd에_전달하고_verified_warm_start만_연결한다() {
        PlanningProblem problem = problem();
        FullScoreCalculator full = new FullScoreCalculator();
        RosterSolution root = scored(problem, full, 0, 0, 0);
        RosterSolution improved = scored(problem, full, 1, 0, 0);
        AtomicReference<RosterSolution> vndInput = new AtomicReference<>();
        SolverEngine alns = (ignored, options, listener) -> {
            listener.onBestSolution(improved);
            return new SolveResult<>(improved, improved.score(), TerminationReason.CONVERGED,
                    1L, 3L, 0L, options.randomSeed(), SolveMetrics.empty());
        };
        SolverEngine vnd = (ignored, options, listener) -> {
            vndInput.set(options.warmStart().orElseThrow());
            return new SolveResult<>(vndInput.get(), vndInput.get().score(), TerminationReason.CONVERGED,
                    0L, 0L, 0L, options.randomSeed(), SolveMetrics.empty());
        };
        List<RosterScore> callbacks = new ArrayList<>();
        AlnsChangeSwapVndHybridSolverEngine engine = new AlnsChangeSwapVndHybridSolverEngine(
                AlnsChangeSwapVndHybridSolverEngine.Mode.ALNS_THEN_CHANGE_SWAP,
                alns, vnd, vnd, full);

        SolveResult<RosterSolution> result = engine.solve(problem,
                SolveOptions.builder().warmStart(root).maxEvaluations(10).randomSeed(901L).build(),
                solution -> callbacks.add(solution.score()));

        AlnsChangeSwapVndHybridMetrics metrics = (AlnsChangeSwapVndHybridMetrics) result.metrics();
        assertEquals(improved.score(), result.score());
        assertEquals(improved.score(), vndInput.get().score());
        assertEquals(3L, result.evaluationCount());
        assertEquals(8L, metrics.stages().getFirst().availableEvaluationBudget());
        assertEquals(7L, metrics.stages().getLast().availableEvaluationBudget());
        assertEquals(2L, metrics.stages().getLast().reservedEvaluationBudget());
        assertEquals(List.of(root.score(), improved.score()), callbacks);
        assertTrue(metrics.stages().stream().allMatch(stage -> stage.inputScore() != null && stage.outputScore() != null));
    }

    @Test
    void 첫_stage의_deadline은_전체_deadline이_아니므로_다음_vnd_stage를_실행한다() {
        PlanningProblem problem = problem();
        FullScoreCalculator full = new FullScoreCalculator();
        RosterSolution root = scored(problem, full, 0, 0, 0);
        AtomicReference<Boolean> vndExecuted = new AtomicReference<>(false);
        SolverEngine stageDeadline = (ignored, options, listener) -> new SolveResult<>(
                options.warmStart().orElseThrow(), options.warmStart().orElseThrow().score(),
                TerminationReason.DEADLINE_REACHED, 0L, 0L, 0L, options.randomSeed(), SolveMetrics.empty());
        SolverEngine vnd = (ignored, options, listener) -> {
            vndExecuted.set(true);
            RosterSolution warm = options.warmStart().orElseThrow();
            return new SolveResult<>(warm, warm.score(), TerminationReason.CONVERGED,
                    0L, 0L, 0L, options.randomSeed(), SolveMetrics.empty());
        };
        AlnsChangeSwapVndHybridSolverEngine engine = new AlnsChangeSwapVndHybridSolverEngine(
                AlnsChangeSwapVndHybridSolverEngine.Mode.ALNS_THEN_CHANGE_SWAP,
                stageDeadline, vnd, vnd, full);

        SolveResult<RosterSolution> result = engine.solve(problem,
                SolveOptions.builder().warmStart(root).spentLimit(java.time.Duration.ofSeconds(1)).randomSeed(902L).build(),
                ignored -> { });

        assertTrue(vndExecuted.get());
        assertEquals(2, ((AlnsChangeSwapVndHybridMetrics) result.metrics()).stages().size());
    }

    private static PlanningProblem problem() {
        LocalDate first = LocalDate.of(2026, 4, 1);
        return new PlanningProblem(new PlanningProblem.ScheduleWindow(
                "tenant", "hybrid-vnd", 0, 31, first, first.minusDays(1)),
                List.of(employee("e0"), employee("e1")),
                List.of(shift(1L, first), shift(2L, first.plusDays(1)), shift(3L, first.plusDays(2))), List.of());
    }

    private static RosterSolution scored(PlanningProblem problem, FullScoreCalculator full, int... assignments) {
        RosterSolution provisional = new RosterSolution(problem.employeeCount(), assignments, RosterScore.of(0, 0, 0, 0, 0));
        return new RosterSolution(problem.employeeCount(), assignments, full.calculateScore(problem, provisional));
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
