package org.acme.solver.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.acme.model.Availability;
import org.acme.model.AvailabilityType;
import org.acme.model.Employee;
import org.acme.model.EmployeeSchedule;
import org.acme.model.ScheduleState;
import org.acme.model.Shift;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.optaplanner.OptaPlannerScoreAdapter;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * 엔진 중립 문제/해를 기존 EmployeeSchedule 출력 모델로 투영합니다.
 */
@ApplicationScoped
public class EmployeeScheduleProjection {

    public EmployeeSchedule toEmployeeSchedule(PlanningProblem problem) {
        return toEmployeeSchedule(problem, null);
    }

    public EmployeeSchedule toEmployeeSchedule(PlanningProblem problem, RosterSolution solution) {
        Objects.requireNonNull(problem, "problem");
        if (solution != null) {
            validateSolutionShape(problem, solution);
        }

        List<Employee> employees = projectEmployees(problem.employees());
        List<Shift> shifts = new ArrayList<>(problem.shiftCount());
        for (int shiftIndex = 0; shiftIndex < problem.shiftCount(); shiftIndex++) {
            PlanningProblem.ShiftData source = problem.shifts().get(shiftIndex);
            int employeeIndex = solution == null
                    ? source.initialEmployeeIndex()
                    : solution.employeeIndex(shiftIndex);
            Employee employee = employeeIndex < 0 ? null : employees.get(employeeIndex);

            Shift shift = new Shift(
                    source.planningId(),
                    source.externalId(),
                    source.start(),
                    source.end(),
                    source.location(),
                    source.requiredSkill(),
                    employee,
                    source.explicitlyPinned());
            shift.setShiftCode(source.shiftCode());
            shift.setNightBurdenScore(source.nightBurdenScore());
            shift.setHolidayBurdenScore(source.holidayBurdenScore());
            shifts.add(shift);
        }

        List<Availability> availabilities = new ArrayList<>(problem.availabilities().size());
        for (PlanningProblem.AvailabilityData source : problem.availabilities()) {
            Availability availability = new Availability(
                    employees.get(source.employeeIndex()),
                    source.date(),
                    AvailabilityType.valueOf(source.kind().name()));
            availability.setId(source.planningId());
            availabilities.add(availability);
        }

        EmployeeSchedule schedule = new EmployeeSchedule(
                projectScheduleState(problem.scheduleWindow()),
                availabilities,
                employees,
                shifts);
        if (solution != null) {
            schedule.setScore(OptaPlannerScoreAdapter.toBendableScore(solution.score()));
        }
        return schedule;
    }

    public RosterSolution toRosterSolution(PlanningProblem problem, EmployeeSchedule schedule) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(schedule, "schedule");
        if (schedule.getScore() == null) {
            throw new IllegalArgumentException("complete solution에는 score가 필요합니다.");
        }

        Map<String, Integer> employeeIndexById = problem.employeeIndexByExternalId();
        int[] assignments = new int[problem.shiftCount()];
        boolean[] seen = new boolean[problem.shiftCount()];
        for (Shift shift : Objects.requireNonNull(schedule.getShiftList(), "schedule.shiftList")) {
            Integer shiftIndex = problem.shiftIndexByPlanningId().get(shift.getId());
            if (shiftIndex == null) {
                throw new IllegalArgumentException("PlanningProblem에 없는 shift planning ID: " + shift.getId());
            }
            if (seen[shiftIndex]) {
                throw new IllegalArgumentException("중복 shift planning ID: " + shift.getId());
            }
            Employee employee = shift.getEmployee();
            if (employee == null) {
                throw new IllegalArgumentException("미배정 shift는 RosterSolution으로 투영할 수 없습니다: " + shift.getId());
            }
            Integer employeeIndex = employeeIndexById.get(employee.getId());
            if (employeeIndex == null) {
                throw new IllegalArgumentException("PlanningProblem에 없는 employee ID: " + employee.getId());
            }
            assignments[shiftIndex] = employeeIndex;
            seen[shiftIndex] = true;
        }
        for (int shiftIndex = 0; shiftIndex < seen.length; shiftIndex++) {
            if (!seen[shiftIndex]) {
                throw new IllegalArgumentException("solution에 shift가 누락되었습니다: shiftIndex=" + shiftIndex);
            }
        }

        return new RosterSolution(
                problem.employeeCount(),
                assignments,
                OptaPlannerScoreAdapter.toRosterScore(schedule.getScore()));
    }

    private static List<Employee> projectEmployees(List<PlanningProblem.EmployeeData> sources) {
        List<Employee> employees = new ArrayList<>(sources.size());
        for (PlanningProblem.EmployeeData source : sources) {
            Employee employee = new Employee(
                    source.externalId(),
                    source.name(),
                    new java.util.LinkedHashSet<>(source.availableShiftCodes()),
                    new java.util.LinkedHashSet<>(source.skillSet()));
            employee.setYearlyNightWorkCount(source.yearlyNightWorkCount());
            employee.setYearlyHolidayWorkCount(source.yearlyHolidayWorkCount());
            employee.setYearlyOffRequestCount(source.yearlyOffRequestCount());
            employee.setOffRequestPenaltyWeight(source.offRequestPenaltyWeight());
            employee.setPreceptorId(source.preceptorExternalId());
            employees.add(employee);
        }
        return employees;
    }

    private static ScheduleState projectScheduleState(PlanningProblem.ScheduleWindow source) {
        ScheduleState state = new ScheduleState();
        state.setTenantId(source.tenantId());
        state.setName(source.name());
        state.setPublishLength(source.publishLength());
        state.setDraftLength(source.draftLength());
        state.setFirstDraftDate(source.firstDraftDate());
        state.setLastHistoricDate(source.lastHistoricDate());
        return state;
    }

    private static void validateSolutionShape(PlanningProblem problem, RosterSolution solution) {
        if (solution.employeeCount() != problem.employeeCount()) {
            throw new IllegalArgumentException("solution employeeCount가 PlanningProblem과 다릅니다.");
        }
        if (solution.shiftCount() != problem.shiftCount()) {
            throw new IllegalArgumentException("solution shiftCount가 PlanningProblem과 다릅니다.");
        }
    }
}
