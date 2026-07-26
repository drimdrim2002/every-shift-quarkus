package org.acme.solver.initial;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.EmployeeScheduleBuilder;
import org.acme.solver.adapter.PlanningProblemMapper;
import org.acme.solver.core.PlanningProblem;
import org.acme.test.JsonLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;

class InitialSolutionBuilderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private final InitialSolutionBuilder builder = new InitialSolutionBuilder();

    @Test
    void hard_free_후보로_complete_feasible_초기해를_만든다() {
        PlanningProblem problem = problem(
                List.of(employee("a", null, Set.of("ALL")), employee("b", null, Set.of("ALL"))),
                List.of(
                        shift(1, LocalDate.of(2026, 1, 1), "D", "ALL", false, -1),
                        shift(2, LocalDate.of(2026, 1, 1), "D", "ALL", false, -1)));

        InitialSolutionResult result = builder.build(problem);

        assertTrue(result.succeeded());
        assertTrue(result.feasible());
        assertEquals(2, result.solution().shiftCount());
        assertEquals(0, result.solution().score().hardScore());
    }

    @Test
    void hard_free_후보가_없으면_complete_but_infeasible_초기해를_만든다() {
        PlanningProblem problem = problem(
                List.of(employee("a", null, Set.of("OTHER"))),
                List.of(shift(1, LocalDate.of(2026, 1, 1), "D", "ALL", false, -1)));

        InitialSolutionResult result = builder.build(problem);

        assertTrue(result.succeeded());
        assertFalse(result.feasible());
        assertTrue(result.solution().score().hardScore() < 0);
        assertEquals(0, result.solution().employeeIndex(0));
    }

    @Test
    void relation_group을_원자적으로_완성하고_pinned_assignment를_보존한다() {
        PlanningProblem problem = problem(
                List.of(employee("preceptor", null, Set.of("ALL")),
                        employee("preceptee", "preceptor", Set.of("ALL"))),
                List.of(
                        shift(1, LocalDate.of(2026, 1, 1), "D", "ALL", true, 0),
                        shift(2, LocalDate.of(2026, 1, 1), "D", "ALL", false, -1)));

        InitialSolutionResult result = builder.build(problem);

        assertTrue(result.succeeded());
        assertTrue(result.feasible());
        assertArrayEquals(new int[] { 0, 1 }, result.solution().employeeIndexByShift());
    }

    @Test
    void 한_preceptor의_복수_preceptee도_하나의_relation_group으로_배정한다() {
        PlanningProblem problem = problem(
                List.of(employee("preceptor", null, Set.of("ALL")),
                        employee("preceptee-a", "preceptor", Set.of("ALL")),
                        employee("preceptee-b", "preceptor", Set.of("ALL"))),
                List.of(
                        shift(1, LocalDate.of(2026, 1, 1), "D", "ALL", true, 0),
                        shift(2, LocalDate.of(2026, 1, 1), "D", "ALL", false, -1),
                        shift(3, LocalDate.of(2026, 1, 1), "D", "ALL", false, -1)));

        InitialSolutionResult result = builder.build(problem);

        assertTrue(result.succeeded());
        assertTrue(result.feasible());
        assertArrayEquals(new int[] { 0, 1, 2 }, result.solution().employeeIndexByShift());
    }

    @Test
    void historic와_published_assignment를_선적용하고_보존한다() {
        PlanningProblem problem = new PlanningProblem(
                new PlanningProblem.ScheduleWindow(
                        "tenant", "name", 1, 10,
                        LocalDate.of(2026, 1, 3), LocalDate.of(2026, 1, 1)),
                List.of(employee("a", null, Set.of("ALL")), employee("b", null, Set.of("ALL"))),
                List.of(
                        shift(1, LocalDate.of(2026, 1, 1), "D", "ALL", false, 0),
                        shift(2, LocalDate.of(2026, 1, 2), "D", "ALL", false, 1),
                        shift(3, LocalDate.of(2026, 1, 3), "D", "ALL", false, -1)),
                List.of());

        InitialSolutionResult result = builder.build(problem);

        assertTrue(result.succeeded());
        assertEquals(0, result.solution().employeeIndex(0));
        assertEquals(1, result.solution().employeeIndex(1));
    }

    @Test
    void 고정_배정_충돌은_구조적_실패로_반환한다() {
        PlanningProblem problem = problem(
                List.of(employee("a", null, Set.of("ALL"))),
                List.of(
                        shift(1, LocalDate.of(2026, 1, 1), "D", "ALL", true, 0),
                        shift(2, LocalDate.of(2026, 1, 1), "D", "ALL", true, 0)));

        InitialSolutionResult result = builder.build(problem);

        assertFalse(result.succeeded());
        assertEquals(InitialSolutionFailureCode.FIXED_ASSIGNMENT_CONFLICT,
                result.failures().getFirst().code());
    }

    @Test
    void 배정할_직원이_없거나_relation_cycle이면_탐색_전_실패한다() {
        PlanningProblem noEmployee = problem(
                List.of(), List.of(shift(1, LocalDate.of(2026, 1, 1), "D", "ALL", false, -1)));
        InitialSolutionResult noEmployeeResult = builder.build(noEmployee);
        assertEquals(InitialSolutionFailureCode.NO_EMPLOYEE,
                noEmployeeResult.failures().getFirst().code());

        PlanningProblem cycle = problem(
                List.of(employee("a", "b", Set.of("ALL")), employee("b", "a", Set.of("ALL"))),
                List.of(
                        shift(1, LocalDate.of(2026, 1, 1), "D", "ALL", false, -1),
                        shift(2, LocalDate.of(2026, 1, 1), "D", "ALL", false, -1)));
        InitialSolutionResult cycleResult = builder.build(cycle);
        assertEquals(InitialSolutionFailureCode.CYCLIC_PRECEPTOR_RELATION,
                cycleResult.failures().getFirst().code());

        PlanningProblem unsupportedCode = new PlanningProblem(
                new PlanningProblem.ScheduleWindow(
                        "tenant", "name", 0, 31,
                        LocalDate.of(2026, 1, 1), LocalDate.of(2025, 12, 31)),
                List.of(new PlanningProblem.EmployeeData(
                        "a", "a", Set.of("ALL"), Set.of("E"), 0, 0, 0, 1, null)),
                List.of(shift(1, LocalDate.of(2026, 1, 1), "D", "ALL", false, -1)),
                List.of());
        InitialSolutionResult unsupportedCodeResult = builder.build(unsupportedCode);
        assertEquals(InitialSolutionFailureCode.NO_ASSIGNABLE_EMPLOYEE,
                unsupportedCodeResult.failures().getFirst().code());
    }

    @ParameterizedTest
    @ValueSource(strings = { "fairness.json", "preceptor.json", "request.json", "sample.json" })
    void 운영_입력은_모두_complete_feasible_초기해를_만든다(String dataset) throws Exception {
        PlanningRequest request = OBJECT_MAPPER.readValue(
                JsonLoader.loadAsString("/json/" + dataset), PlanningRequest.class);
        PlanningProblem problem = new PlanningProblemMapper().toPlanningProblem(
                new EmployeeScheduleBuilder().build(request));

        InitialSolutionResult first = builder.build(problem);
        InitialSolutionResult second = builder.build(problem);

        assertTrue(first.succeeded(), () -> dataset + ": " + first.failures());
        assertEquals(problem.shiftCount(), first.solution().shiftCount());
        assertArrayEquals(first.solution().employeeIndexByShift(), second.solution().employeeIndexByShift());
        assertEquals(first.solution().score(), second.solution().score());
        assertNotNull(first.solution());
    }

    private static PlanningProblem problem(
            List<PlanningProblem.EmployeeData> employees,
            List<PlanningProblem.ShiftData> shifts) {
        return new PlanningProblem(
                new PlanningProblem.ScheduleWindow(
                        "tenant", "name", 0, 31,
                        LocalDate.of(2026, 1, 1), LocalDate.of(2025, 12, 31)),
                employees,
                shifts,
                List.of());
    }

    private static PlanningProblem.EmployeeData employee(
            String id, String preceptorId, Set<String> skills) {
        return new PlanningProblem.EmployeeData(
                id, id, skills, Set.of("D", "E", "N"), 0, 0, 0, 1, preceptorId);
    }

    private static PlanningProblem.ShiftData shift(
            long id,
            LocalDate date,
            String code,
            String skill,
            boolean pinned,
            int employeeIndex) {
        LocalDateTime start = switch (code) {
            case "D" -> date.atTime(8, 0);
            case "E" -> date.atTime(16, 0);
            case "N" -> date.atStartOfDay();
            default -> date.atTime(8, 0);
        };
        return new PlanningProblem.ShiftData(
                id, "shift-" + id, code, start, start.plusHours(8), date,
                "location", skill, pinned, employeeIndex, "N".equals(code) ? 1 : 0, 0);
    }
}
