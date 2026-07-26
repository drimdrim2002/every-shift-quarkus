package org.acme.solver.lahc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.core.TerminationReason;
import org.acme.solver.initial.InitialSolutionBuilder;
import org.acme.solver.move.Move;
import org.acme.solver.move.ReassignMove;
import org.acme.solver.move.SearchState;
import org.acme.solver.move.SwapMove;
import org.acme.solver.score.FullScoreCalculator;
import org.junit.jupiter.api.Test;

class LahcSolverEngineTest {

    private final FullScoreCalculator full = new FullScoreCalculator();

    @Test
    void no_op_선택은_평가와_정체에_포함하지_않는다() {
        PlanningProblem problem = plateauProblem();
        LahcSolverEngine engine = engine((ignoredProblem, state, random) -> Optional.empty());

        SolveResult<RosterSolution> result = engine.solve(
                problem,
                SolveOptions.builder()
                        .maxEvaluations(10)
                        .maxStagnantEvaluations(1)
                        .randomSeed(42)
                        .build(),
                solution -> {
                });

        assertEquals(TerminationReason.CONVERGED, result.terminationReason());
        assertEquals(0, result.evaluationCount());
        assertEquals(0, result.iterations());
    }

    @Test
    void 동점_assignment_move는_수락하지만_lastBestImprovementEvaluation_기준으로_정체_종료한다() {
        PlanningProblem problem = plateauProblem();
        LahcMoveSelector selector = (ignoredProblem, state, random) -> Optional.of(
                SwapMove.create(problem, state, 0, 1));
        LahcSolverEngine engine = engine(selector);

        SolveResult<RosterSolution> result = engine.solve(
                problem,
                SolveOptions.builder()
                        .maxEvaluations(20)
                        .maxStagnantEvaluations(2)
                        .randomSeed(42)
                        .build(),
                solution -> {
                });

        assertEquals(TerminationReason.CONVERGED, result.terminationReason());
        assertEquals(2, result.evaluationCount());
        assertEquals(2, result.iterations());
    }

    @Test
    void maxIterations는_evaluation_budget과_독립적으로_종료한다() {
        PlanningProblem problem = plateauProblem();
        LahcMoveSelector selector = (ignoredProblem, state, random) -> Optional.of(
                SwapMove.create(problem, state, 0, 1));

        SolveResult<RosterSolution> result = engine(selector).solve(
                problem,
                SolveOptions.builder()
                        .maxEvaluations(10)
                        .maxIterations(1)
                        .randomSeed(42)
                        .build(),
                solution -> {
                });

        assertEquals(TerminationReason.MAX_ITERATIONS_REACHED, result.terminationReason());
        assertEquals(1, result.evaluationCount());
        assertEquals(1, result.iterations());
    }

    @Test
    void current와_history보다_나쁜_후보는_거절하고_assignment를_rollback한다() {
        PlanningProblem problem = skillSensitiveProblem();
        RosterSolution initial = new InitialSolutionBuilder(full).build(problem).solution();
        int assigned = initial.employeeIndex(0);
        int worseEmployee = assigned == 0 ? 1 : 0;
        LahcMoveSelector selector = (ignoredProblem, state, random) -> Optional.of(
                ReassignMove.create(problem, state, 0, worseEmployee));

        SolveResult<RosterSolution> result = engine(selector).solve(
                problem,
                SolveOptions.builder().maxEvaluations(1).warmStart(initial).build(),
                solution -> {
                });

        assertEquals(TerminationReason.MAX_EVALUATIONS_REACHED, result.terminationReason());
        assertEquals(1, result.evaluationCount());
        assertArrayEquals(initial.employeeIndexByShift(), result.bestSolution().employeeIndexByShift());
        assertEquals(initial.score(), result.score());
    }

    @Test
    void callback_예외가_발생해도_검증된_best를_반환한다() {
        PlanningProblem problem = plateauProblem();
        AtomicInteger callbacks = new AtomicInteger();
        LahcSolverEngine engine = engine((ignoredProblem, state, random) -> Optional.empty());

        SolveResult<RosterSolution> result = engine.solve(
                problem,
                SolveOptions.builder().maxEvaluations(1).randomSeed(1).build(),
                solution -> {
                    callbacks.incrementAndGet();
                    throw new IllegalStateException("listener failure");
                });

        assertNotNull(result.bestSolution());
        assertEquals(1, callbacks.get());
        assertEquals(0, full.calculateScore(problem, result.bestSolution()).hardScore());
    }

    @Test
    void transaction_중_cancellation은_rollback하고_마지막_verified_best를_반환한다() {
        PlanningProblem problem = oneShiftProblem();
        RosterSolution initial = new InitialSolutionBuilder(full).build(problem).solution();
        AtomicInteger cancellationChecks = new AtomicInteger();
        LahcMoveSelector selector = (ignoredProblem, state, random) -> Optional.of(
                ReassignMove.create(problem, state, 0, 1));

        SolveResult<RosterSolution> result = engine(selector).solve(
                problem,
                SolveOptions.builder()
                        .maxEvaluations(10)
                        .warmStart(initial)
                        .cancellationToken(() -> cancellationChecks.incrementAndGet() >= 3)
                        .build(),
                solution -> {
                });

        assertEquals(TerminationReason.CANCELLED, result.terminationReason());
        assertEquals(0, result.evaluationCount());
        assertArrayEquals(initial.employeeIndexByShift(), result.bestSolution().employeeIndexByShift());
        assertEquals(initial.score(), result.score());
    }

    @Test
    void transaction_중_monotonic_deadline은_rollback한다() {
        PlanningProblem problem = oneShiftProblem();
        RosterSolution initial = new InitialSolutionBuilder(full).build(problem).solution();
        LahcMoveSelector selector = (ignoredProblem, state, random) -> Optional.of(
                delayed(ReassignMove.create(problem, state, 0, 1), Duration.ofMillis(5)));

        SolveResult<RosterSolution> result = engine(selector).solve(
                problem,
                SolveOptions.builder()
                        .deadlineNanos(System.nanoTime() + Duration.ofMillis(2).toNanos())
                        .warmStart(initial)
                        .build(),
                solution -> {
                });

        assertEquals(TerminationReason.DEADLINE_REACHED, result.terminationReason());
        assertEquals(0, result.evaluationCount());
        assertArrayEquals(initial.employeeIndexByShift(), result.bestSolution().employeeIndexByShift());
    }

    @Test
    void 같은_seed와_평가_예산은_assignment와_score를_재현한다() {
        PlanningProblem problem = deterministicProblem();
        SolveOptions options = SolveOptions.builder()
                .maxEvaluations(100)
                .randomSeed(20260715L)
                .build();

        SolveResult<RosterSolution> first = new LahcSolverEngine().solve(problem, options, solution -> {
        });
        SolveResult<RosterSolution> second = new LahcSolverEngine().solve(problem, options, solution -> {
        });

        assertArrayEquals(first.bestSolution().employeeIndexByShift(), second.bestSolution().employeeIndexByShift());
        assertEquals(first.score(), second.score());
        assertEquals(first.evaluationCount(), second.evaluationCount());
        assertTrue(first.score().isFeasible());
    }

    @Test
    void 구조적_초기해_실패를_INITIAL_SOLUTION_FAILED로_반환한다() {
        PlanningProblem problem = new PlanningProblem(
                window(), List.of(), List.of(shift(1, LocalDate.of(2026, 1, 1), -1)), List.of());

        SolveResult<RosterSolution> result = new LahcSolverEngine().solve(
                problem,
                SolveOptions.builder().maxEvaluations(1).build(),
                solution -> {
                });

        assertEquals(TerminationReason.INITIAL_SOLUTION_FAILED, result.terminationReason());
        assertEquals(null, result.bestSolution());
    }

    private LahcSolverEngine engine(LahcMoveSelector selector) {
        return new LahcSolverEngine(new InitialSolutionBuilder(full), full, selector, 3);
    }

    private static Move delayed(Move delegate, Duration delay) {
        return new Move() {
            @Override
            public String moveType() {
                return delegate.moveType();
            }

            @Override
            public List<org.acme.solver.move.AssignmentChange> changes() {
                return delegate.changes();
            }

            @Override
            public void apply(SearchState state) {
                delegate.apply(state);
                LockSupport.parkNanos(delay.toNanos());
            }

            @Override
            public void undo(SearchState state) {
                delegate.undo(state);
            }
        };
    }

    private static PlanningProblem plateauProblem() {
        return new PlanningProblem(
                window(),
                employees(2),
                List.of(
                        shift(1, LocalDate.of(2026, 1, 1), -1),
                        shift(2, LocalDate.of(2026, 1, 1), -1)),
                List.of());
    }

    private static PlanningProblem oneShiftProblem() {
        return new PlanningProblem(
                window(), employees(2),
                List.of(shift(1, LocalDate.of(2026, 1, 1), -1)), List.of());
    }

    private static PlanningProblem skillSensitiveProblem() {
        return new PlanningProblem(
                window(),
                List.of(
                        new PlanningProblem.EmployeeData(
                                "qualified", "qualified", Set.of("ALL"), Set.of("D"),
                                0, 0, 0, 1, null),
                        new PlanningProblem.EmployeeData(
                                "unqualified", "unqualified", Set.of("OTHER"), Set.of("D"),
                                0, 0, 0, 1, null)),
                List.of(shift(1, LocalDate.of(2026, 1, 1), -1)),
                List.of());
    }

    private static PlanningProblem deterministicProblem() {
        List<PlanningProblem.ShiftData> shifts = new java.util.ArrayList<>();
        long id = 1L;
        for (int day = 0; day < 7; day++) {
            LocalDate date = LocalDate.of(2026, 1, 1).plusDays(day);
            shifts.add(shift(id++, date, -1));
            shifts.add(shift(id++, date, -1));
        }
        return new PlanningProblem(window(), employees(4), shifts, List.of());
    }

    private static List<PlanningProblem.EmployeeData> employees(int count) {
        List<PlanningProblem.EmployeeData> result = new java.util.ArrayList<>();
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
                "location", "ALL", false, employeeIndex, 0, 0);
    }

    private static PlanningProblem.ScheduleWindow window() {
        return new PlanningProblem.ScheduleWindow(
                "tenant", "name", 0, 31,
                LocalDate.of(2026, 1, 1), LocalDate.of(2025, 12, 31));
    }
}
