package org.acme.solver.util;

import jakarta.enterprise.context.ApplicationScoped;
import org.acme.api.dto.PlanningRequest;
import org.acme.solver.model.Employee;
import org.acme.solver.model.EmployeeSchedule;
import org.acme.solver.model.ScheduleState;
import org.acme.solver.model.Shift;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

@ApplicationScoped
public class DtoConverter {

    public EmployeeSchedule toEmployeeSchedule(PlanningRequest planningRequest) {

        List<PlanningRequest.EmployeeInfo> employees = planningRequest.employees();
        Map<String, Employee> employeeMap = getEmployees(employees);

        ScheduleState scheduleState = getScheduleState(planningRequest.organization());

        List<PlanningRequest.ShiftInfo> shifts = planningRequest.organization().shifts();
        Map<String, PlanningRequest.ShiftInfo> shiftCodeMap = new HashMap<>();
        Map<String, PlanningRequest.ShiftInfo> shiftIdMap = new HashMap<>();
        
        for (PlanningRequest.ShiftInfo shift : shifts) {
            shiftCodeMap.put(shift.code(), shift);
            shiftIdMap.put(shift.id(), shift);
        }

        List<Shift> shiftList = new ArrayList<>();
        // Map<LocalDate, Map<ShiftId, Deque<Shift>>>
        // 날짜별, Shift ID별로 생성된 Shift 객체들을 관리하여 Assignment 매핑 시 사용
        Map<LocalDate, Map<String, Deque<Shift>>> shiftLookup = new HashMap<>();

        // 1. Requirements(요구사항)을 기반으로 빈 Shift 슬롯 생성
        if (planningRequest.requirements() != null) {
            for (Map.Entry<String, Map<String, Integer>> dateEntry : planningRequest.requirements().entrySet()) {
                LocalDate date = LocalDate.parse(dateEntry.getKey());
                Map<String, Integer> reqs = dateEntry.getValue();

                for (Map.Entry<String, Integer> req : reqs.entrySet()) {
                    String shiftCode = req.getKey();
                    
                    // "total" 같은 메타 데이터는 건너뜀
                    if ("total".equalsIgnoreCase(shiftCode)) continue;

                    int count = req.getValue();
                    PlanningRequest.ShiftInfo info = shiftCodeMap.get(shiftCode);
                    if (info == null) {
                        // 정의되지 않은 Shift Code는 무시
                        continue;
                    }

                    for (int i = 0; i < count; i++) {
                        Shift shift = createShift(date, info, planningRequest.organization().name());
                        shiftList.add(shift);

                        // Lookup 맵에 추가
                        shiftLookup.computeIfAbsent(date, d -> new HashMap<>())
                                   .computeIfAbsent(info.id(), id -> new ArrayDeque<>())
                                   .add(shift);
                    }
                }
            }
        }

        // 2. Assignments(기배정) 정보를 Shift 슬롯에 매핑
        if (planningRequest.assignments() != null) {
            for (PlanningRequest.AssignmentInfo assignment : planningRequest.assignments()) {
                LocalDate date = assignment.date();
                String shiftId = assignment.shiftId();
                String empId = assignment.employeeId();
                boolean isLocked = assignment.isLocked();

                Shift targetShift = null;

                // 생성된 Shift 중 가용한 슬롯 찾기
                Map<String, Deque<Shift>> dateShifts = shiftLookup.get(date);
                if (dateShifts != null) {
                    Deque<Shift> deque = dateShifts.get(shiftId);
                    if (deque != null && !deque.isEmpty()) {
                        // 큐에서 하나 꺼내서 사용 (중복 할당 방지)
                        targetShift = deque.poll();
                    }
                }

                // 가용 슬롯이 없으면 새로 생성 (요구사항보다 배정이 많은 경우)
                if (targetShift == null) {
                    PlanningRequest.ShiftInfo info = shiftIdMap.get(shiftId);
                    if (info != null) {
                        targetShift = createShift(date, info, planningRequest.organization().name());
                        shiftList.add(targetShift);
                    }
                }

                // 직원 할당 및 고정 설정
                if (targetShift != null) {
                    Employee employee = employeeMap.get(empId);
                    if (employee != null) {
                        targetShift.setEmployee(employee);
                        targetShift.setPinned(isLocked);
                    }
                }
            }
        }

        EmployeeSchedule employeeSchedule = new EmployeeSchedule();
        employeeSchedule.setEmployeeList(new ArrayList<>(employeeMap.values()));
        employeeSchedule.setScheduleState(scheduleState);
        employeeSchedule.setShiftList(shiftList);

        return employeeSchedule;
    }

    private Shift createShift(LocalDate date, PlanningRequest.ShiftInfo info, String defaultLocation) {
        LocalTime startTime = info.startTime();
        LocalTime endTime = info.endTime();

        LocalDateTime startDateTime = LocalDateTime.of(date, startTime);
        LocalDateTime endDateTime = LocalDateTime.of(date, endTime);

        // 종료 시간이 시작 시간보다 빠르면 다음 날로 간주 (예: 22:00 ~ 06:00)
        if (endTime.isBefore(startTime)) {
            endDateTime = endDateTime.plusDays(1);
        }
        
        // 현재 요구사항에는 skill 정보가 없으므로 "ALL"로 고정
        String requiredSkill = "ALL";

        return new Shift(startDateTime, endDateTime, defaultLocation, requiredSkill, null);
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

    private static Map<String, Employee> getEmployees(List<PlanningRequest.EmployeeInfo> employees) {
        HashMap<String, Employee> employeeMap = new HashMap<>();
        for (PlanningRequest.EmployeeInfo employee : employees) {
            String employeeId = employee.employeeId();
            String name = employee.name();
            Set<String> skillSet = employee.skillSet();
            Set<String> availableShifts = employee.availableShifts();
            employeeMap.put(employeeId, new Employee(employeeId, name, availableShifts, skillSet));
        }
        return employeeMap;
    }
}
