package org.acme.solver.optaplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.acme.model.Employee;
import org.acme.model.EmployeeSchedule;
import org.acme.model.ScheduleState;
import org.acme.model.Shift;
import org.acme.solver.adapter.EmployeeScheduleProjection;
import org.acme.solver.adapter.PlanningProblemMapper;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.core.TerminationReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OptaPlannerSolverEngineTest {

    private final PlanningProblemMapper mapper = new PlanningProblemMapper();
    private final EmployeeScheduleProjection projection = new EmployeeScheduleProjection();
    private OptaPlannerSolverEngine engine;

    @BeforeEach
    void setUp() {
        engine = new OptaPlannerSolverEngine();
        engine.projection = projection;
        engine.moveThreadCount = "NONE";
        engine.environmentMode = "REPRODUCIBLE";
    }

    @Test
    void 기존_OptaPlanner를_엔진_경계_뒤에서_실행하고_warmStart를_보존한다() {
        PlanningProblem problem = mapper.toPlanningProblem(simpleProblem());
        AtomicInteger callbackCount = new AtomicInteger();
        SolveOptions firstOptions = SolveOptions.builder()
                .spentLimit(Duration.ofMillis(100))
                .randomSeed(42)
                .build();

        SolveResult<RosterSolution> first = engine.solve(
                problem,
                firstOptions,
                solution -> callbackCount.incrementAndGet());

        assertNotNull(first.bestSolution());
        assertEquals(problem.shiftCount(), first.bestSolution().shiftCount());
        assertEquals(TerminationReason.COMPLETED, first.terminationReason());
        assertEquals(1, callbackCount.get());

        SolveResult<RosterSolution> warmed = engine.solve(
                problem,
                firstOptions.toBuilder().warmStart(first.bestSolution()).build(),
                solution -> {
                    throw new IllegalStateException("listener failure");
                });

        assertNotNull(warmed.bestSolution());
        assertTrue(warmed.score().compareTo(first.score()) >= 0);
    }

    @Test
    void solve_시작_전_cancellation은_불완전해를_노출하지_않는다() {
        PlanningProblem problem = mapper.toPlanningProblem(simpleProblem());
        SolveOptions options = SolveOptions.builder()
                .spentLimit(Duration.ofSeconds(1))
                .cancellationToken(() -> true)
                .build();

        SolveResult<RosterSolution> result = engine.solve(problem, options, solution -> {
        });

        assertEquals(TerminationReason.CANCELLED, result.terminationReason());
        assertNull(result.bestSolution());
        assertNull(result.score());
    }

    private static EmployeeSchedule simpleProblem() {
        Employee employeeA = new Employee("a", "A", Set.of("D"), Set.of("ALL"));
        Employee employeeB = new Employee("b", "B", Set.of("D"), Set.of("ALL"));

        Shift shift = new Shift(
                1L,
                "day",
                LocalDateTime.of(2026, 6, 1, 8, 0),
                LocalDateTime.of(2026, 6, 1, 16, 0),
                "location",
                "ALL",
                null);
        shift.setShiftCode("D");

        ScheduleState state = new ScheduleState();
        state.setTenantId("tenant");
        state.setName("organization");
        state.setLastHistoricDate(LocalDate.of(2026, 5, 31));
        state.setFirstDraftDate(LocalDate.of(2026, 6, 1));
        state.setPublishLength(0);
        state.setDraftLength(1);
        return new EmployeeSchedule(state, List.of(), List.of(employeeA, employeeB), List.of(shift));
    }
}
