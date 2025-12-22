package org.acme.solver.util;

import jakarta.enterprise.context.ApplicationScoped;
import org.acme.api.dto.PlanningRequest;
import org.acme.solver.model.Employee;
import org.acme.solver.model.EmployeeSchedule;
import org.acme.solver.model.ScheduleState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class DtoConverter {

    public EmployeeSchedule toEmployeeSchedule(PlanningRequest planningRequest) {


        List<PlanningRequest.EmployeeInfo> employees = planningRequest.employees();

        List<Employee> employeeList = getEmployees(employees);

        ScheduleState scheduleState = getScheduleState(planningRequest.organization());

        EmployeeSchedule employeeSchedule = new EmployeeSchedule();
        employeeSchedule.setEmployeeList(employeeList);
        employeeSchedule.setScheduleState(scheduleState);

        // TODO: PlanningRequest를 EmployeeSchedule로 변환하는 로직 구현 예정
        return employeeSchedule;
    }

    private static ScheduleState getScheduleState(PlanningRequest.OrganizationInfo organizationInfo) {
        ScheduleState scheduleState = new ScheduleState();
        scheduleState.setTenantId(organizationInfo.id());
        scheduleState.setName(organizationInfo.name());
        scheduleState.setDraftLength(organizationInfo.draftLength());
        scheduleState.setLastHistoricDate(organizationInfo.lastHistoricalDate());
        scheduleState.setFirstDraftDate(organizationInfo.firstDraftDate());
        scheduleState.setPublishLength(organizationInfo.publishLength());
        return scheduleState;
    }

    private static List<Employee> getEmployees(List<PlanningRequest.EmployeeInfo> employees) {
        List<Employee> employeeList = new ArrayList<>();
        for (PlanningRequest.EmployeeInfo employee : employees) {
            String employeeId = employee.employeeId();
            String name = employee.name();
            Set<String> availableShifts = new HashSet<>(employee.availableShifts()) ;

            String id = name + "(" + employeeId + ")";
            employeeList.add(new Employee(id, availableShifts));
        }
        return employeeList;
    }


}
