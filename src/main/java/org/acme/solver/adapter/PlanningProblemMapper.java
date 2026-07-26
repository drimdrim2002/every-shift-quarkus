package org.acme.solver.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.acme.model.Availability;
import org.acme.model.Employee;
import org.acme.model.EmployeeSchedule;
import org.acme.model.ScheduleState;
import org.acme.model.Shift;
import org.acme.solver.ShiftDateMatcher;
import org.acme.solver.core.PlanningProblem;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * 외부 스케줄 모델을 엔진 중립 계획 문제로 복사합니다.
 */
@ApplicationScoped
public class PlanningProblemMapper {

    public PlanningProblem toPlanningProblem(EmployeeSchedule schedule) {
        Objects.requireNonNull(schedule, "schedule");
        ScheduleState state = Objects.requireNonNull(schedule.getScheduleState(), "schedule.scheduleState");
        List<Employee> sourceEmployees = List.copyOf(
                Objects.requireNonNull(schedule.getEmployeeList(), "schedule.employeeList"));
        List<Shift> sourceShifts = List.copyOf(
                Objects.requireNonNull(schedule.getShiftList(), "schedule.shiftList"));
        List<Availability> sourceAvailabilities = schedule.getAvailabilityList() == null
                ? List.of()
                : List.copyOf(schedule.getAvailabilityList());

        PlanningProblem.ScheduleWindow scheduleWindow = new PlanningProblem.ScheduleWindow(
                state.getTenantId(),
                state.getName(),
                state.getPublishLength(),
                state.getDraftLength(),
                state.getFirstDraftDate(),
                state.getLastHistoricDate());

        List<PlanningProblem.EmployeeData> employees = new ArrayList<>(sourceEmployees.size());
        for (Employee employee : sourceEmployees) {
            employees.add(new PlanningProblem.EmployeeData(
                    employee.getId(),
                    employee.getName(),
                    employee.getSkillSet(),
                    employee.getAvailableShift(),
                    employee.getYearlyNightWorkCount(),
                    employee.getYearlyHolidayWorkCount(),
                    employee.getYearlyOffRequestCount(),
                    employee.getOffRequestPenaltyWeight(),
                    employee.getPreceptorId()));
        }

        Map<String, Integer> employeeIndexById = indexEmployees(employees);
        List<PlanningProblem.ShiftData> shifts = new ArrayList<>(sourceShifts.size());
        for (Shift shift : sourceShifts) {
            Long planningId = Objects.requireNonNull(shift.getId(), "shift.id");
            int employeeIndex = shift.getEmployee() == null
                    ? -1
                    : requiredEmployeeIndex(employeeIndexById, shift.getEmployee().getId());
            shifts.add(new PlanningProblem.ShiftData(
                    planningId,
                    shift.getSupabaseId(),
                    shift.getShiftCode(),
                    shift.getStart(),
                    shift.getEnd(),
                    ShiftDateMatcher.resolveLogicalDate(shift.getStart(), shift.getShiftCode()),
                    shift.getLocation(),
                    shift.getRequiredSkill(),
                    shift.isPinned(),
                    employeeIndex,
                    shift.getNightBurdenScore(),
                    shift.getHolidayBurdenScore()));
        }

        List<PlanningProblem.AvailabilityData> availabilities = new ArrayList<>(sourceAvailabilities.size());
        for (Availability availability : sourceAvailabilities) {
            Employee employee = Objects.requireNonNull(availability.getEmployee(), "availability.employee");
            availabilities.add(new PlanningProblem.AvailabilityData(
                    availability.getId(),
                    requiredEmployeeIndex(employeeIndexById, employee.getId()),
                    availability.getDate(),
                    PlanningProblem.AvailabilityKind.valueOf(availability.getAvailabilityType().name())));
        }

        return new PlanningProblem(scheduleWindow, employees, shifts, availabilities);
    }

    private static Map<String, Integer> indexEmployees(List<PlanningProblem.EmployeeData> employees) {
        java.util.LinkedHashMap<String, Integer> index = new java.util.LinkedHashMap<>();
        for (int employeeIndex = 0; employeeIndex < employees.size(); employeeIndex++) {
            String externalId = employees.get(employeeIndex).externalId();
            Integer previous = index.putIfAbsent(externalId, employeeIndex);
            if (previous != null) {
                throw new IllegalArgumentException("중복 employee external ID: " + externalId);
            }
        }
        return index;
    }

    private static int requiredEmployeeIndex(Map<String, Integer> index, String employeeId) {
        Integer employeeIndex = index.get(employeeId);
        if (employeeIndex == null) {
            throw new IllegalArgumentException("스케줄에 없는 employee ID를 참조합니다: " + employeeId);
        }
        return employeeIndex;
    }
}
