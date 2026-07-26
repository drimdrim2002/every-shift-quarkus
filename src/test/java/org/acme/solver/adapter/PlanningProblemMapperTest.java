package org.acme.solver.adapter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.acme.model.Availability;
import org.acme.model.AvailabilityType;
import org.acme.model.Employee;
import org.acme.model.EmployeeSchedule;
import org.acme.model.ScheduleState;
import org.acme.model.Shift;
import org.acme.solver.ShiftDateMatcher;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.junit.jupiter.api.Test;

class PlanningProblemMapperTest {

    private final PlanningProblemMapper mapper = new PlanningProblemMapper();
    private final EmployeeScheduleProjection projection = new EmployeeScheduleProjection();

    @Test
    void index와_pin_분류를_사전_계산하고_원본_순서를_안정적으로_보존한다() {
        EmployeeSchedule schedule = scheduleFixture();

        PlanningProblem problem = mapper.toPlanningProblem(schedule);

        assertEquals(0, problem.employeeIndexByExternalId().get("employee-b"));
        assertEquals(1, problem.employeeIndexByExternalId().get("employee-a"));
        assertEquals(0, problem.shiftIndexByPlanningId().get(101L));
        assertEquals(List.of(0, 1, 2, 3), problem.shiftIndexesByExternalId().get("shift-D"));
        assertArrayEquals(new int[] { 2 }, problem.mutableShiftIndexes());
        assertArrayEquals(new int[] { 0, 1, 3 }, problem.pinnedShiftIndexes());
        assertArrayEquals(new int[] { 1 }, problem.publishedShiftIndexes());
        assertArrayEquals(new int[] { 0 }, problem.historicShiftIndexes());
        assertFalse(problem.isMutableShift(0));
        assertTrue(problem.isMutableShift(2));

        assertThrows(UnsupportedOperationException.class,
                () -> problem.employees().get(0).skillSet().add("변경"));
        int[] mutableSnapshot = problem.mutableShiftIndexes();
        mutableSnapshot[0] = 999;
        assertArrayEquals(new int[] { 2 }, problem.mutableShiftIndexes());
    }

    @Test
    void mapper_왕복에서_ID_pin_assignment와_논리일_정책이_보존된다() {
        EmployeeSchedule schedule = scheduleFixture();
        PlanningProblem problem = mapper.toPlanningProblem(schedule);
        RosterSolution solution = new RosterSolution(
                problem.employeeCount(),
                new int[] { 0, 1, 1, 0 },
                RosterScore.of(0, -1, -2, -3, -4));

        EmployeeSchedule projected = projection.toEmployeeSchedule(problem, solution);
        PlanningProblem remapped = mapper.toPlanningProblem(projected);
        RosterSolution roundTrip = projection.toRosterSolution(remapped, projected);

        assertEquals(List.of("employee-b", "employee-a"),
                projected.getEmployeeList().stream().map(Employee::getId).toList());
        assertEquals(List.of(101L, 102L, 103L, 104L),
                projected.getShiftList().stream().map(Shift::getId).toList());
        assertEquals(List.of(false, false, false, true),
                projected.getShiftList().stream().map(Shift::isPinned).toList());
        assertArrayEquals(solution.employeeIndexByShift(), roundTrip.employeeIndexByShift());
        assertEquals(solution.score(), roundTrip.score());
        assertEquals(problem.shifts().get(3).logicalDate(), remapped.shifts().get(3).logicalDate());
        assertEquals(LocalDate.of(2026, 6, 3), remapped.shifts().get(3).logicalDate());
        assertTrue(ShiftDateMatcher.matchesActualOrLogicalDate(
                remapped.shifts().get(3).start(),
                remapped.shifts().get(3).end(),
                remapped.shifts().get(3).shiftCode(),
                LocalDate.of(2026, 6, 3)));
    }

    private static EmployeeSchedule scheduleFixture() {
        Set<String> mutableSkills = new HashSet<>(Set.of("ALL"));
        Employee employeeB = new Employee("employee-b", "B", Set.of("D", "N"), mutableSkills);
        Employee employeeA = new Employee("employee-a", "A", Set.of("D", "N"), Set.of("ALL"));
        employeeA.setPreceptorId("employee-b");

        ScheduleState state = new ScheduleState();
        state.setTenantId("tenant");
        state.setName("organization");
        state.setLastHistoricDate(LocalDate.of(2026, 6, 1));
        state.setFirstDraftDate(LocalDate.of(2026, 6, 3));
        state.setPublishLength(1);
        state.setDraftLength(10);

        List<Shift> shifts = new ArrayList<>();
        shifts.add(shift(101L, "D", LocalDateTime.of(2026, 6, 1, 8, 0), employeeB, false));
        shifts.add(shift(102L, "D", LocalDateTime.of(2026, 6, 2, 8, 0), employeeA, false));
        shifts.add(shift(103L, "D", LocalDateTime.of(2026, 6, 3, 8, 0), null, false));
        shifts.add(shift(104L, "N", LocalDateTime.of(2026, 6, 4, 0, 0), employeeB, true));

        Availability availability = new Availability(
                employeeA,
                LocalDate.of(2026, 6, 3),
                AvailabilityType.DESIRED);
        availability.setId(501L);
        return new EmployeeSchedule(state, List.of(availability), List.of(employeeB, employeeA), shifts);
    }

    private static Shift shift(
            long id,
            String shiftCode,
            LocalDateTime start,
            Employee employee,
            boolean pinned) {
        Shift shift = new Shift(
                id,
                "shift-D",
                start,
                start.plusHours(8),
                "location",
                "ALL",
                employee,
                pinned);
        shift.setShiftCode(shiftCode);
        shift.setNightBurdenScore(2);
        shift.setHolidayBurdenScore(3);
        return shift;
    }
}
