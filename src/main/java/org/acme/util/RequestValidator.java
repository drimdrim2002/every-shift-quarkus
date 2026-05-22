package org.acme.util;

import org.acme.api.dto.PlanningRequest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PlanningRequest 유효성 검증 유틸리티
 */
public class RequestValidator {

    /**
     * PlanningRequest 유효성 검증
     *
     * @throws ValidationException 유효성 검증 실패 시
     */
    public static void validate(PlanningRequest request) throws ValidationException {
        List<String> errors = new ArrayList<>();

        if (request == null) {
            throw new ValidationException("Request body is required");
        }

        // Organization 검증
        if (request.organization() == null) {
            errors.add("Organization information is required");
        } else {
            if (request.organization().id() == null || request.organization().id().isBlank()) {
                errors.add("Organization ID is required");
            }
            if (request.organization().name() == null || request.organization().name().isBlank()) {
                errors.add("Organization name is required");
            }
            if (request.organization().shifts() == null || request.organization().shifts().isEmpty()) {
                errors.add("At least one shift definition is required");
            }
        }

        // Employees 검증
        if (request.employees() == null || request.employees().isEmpty()) {
            errors.add("At least one employee is required");
        }
        Set<String> knownEmployeeIds = new HashSet<>();
        if (request.employees() != null) {
            for (PlanningRequest.EmployeeInfo employee : request.employees()) {
                if (employee == null || employee.employeeId() == null || employee.employeeId().isBlank()) {
                    continue;
                }
                knownEmployeeIds.add(employee.employeeId());
            }
        }

        // History/Undesirable 검증 (선택사항이지만 null 체크)
        if (request.history() == null) {
            errors.add("History list cannot be null (use empty array if no history)");
        }

        if (request.undesirable() == null) {
            errors.add("Undesirable list cannot be null (use empty array if no undesirable shifts)");
        } else {
            for (int i = 0; i < request.undesirable().size(); i++) {
                PlanningRequest.AssignmentInfo assignment = request.undesirable().get(i);
                if (assignment == null) {
                    errors.add("Undesirable[" + i + "] cannot be null");
                    continue;
                }
                if (assignment.employeeId() == null || assignment.employeeId().isBlank()) {
                    errors.add("Undesirable[" + i + "].employee_id is required");
                } else if (!knownEmployeeIds.isEmpty() && !knownEmployeeIds.contains(assignment.employeeId())) {
                    errors.add("Undesirable[" + i + "].employee_id must exist in employees list");
                }
                if (assignment.date() == null) {
                    errors.add("Undesirable[" + i + "].date is required");
                }
                // Note: undesirable.shift_id / undesirable.is_locked are accepted for compatibility,
                // but are ignored by current solver constraints.
            }
        }

        // Requirements 검증
        if (request.requirements() == null || request.requirements().isEmpty()) {
            errors.add("Requirements are required");
        }

        if (request.publicHolidays() != null) {
            for (int i = 0; i < request.publicHolidays().size(); i++) {
                PlanningRequest.PublicHolidayInfo publicHoliday = request.publicHolidays().get(i);
                if (publicHoliday == null) {
                    errors.add("publicHolidays[" + i + "] cannot be null");
                    continue;
                }
                if (publicHoliday.date() == null) {
                    errors.add("publicHolidays[" + i + "].date is required");
                }
            }
        }

        if (request.yearlyEmployeeStats() != null) {
            for (int i = 0; i < request.yearlyEmployeeStats().size(); i++) {
                PlanningRequest.YearlyEmployeeStatsInfo yearlyStats = request.yearlyEmployeeStats().get(i);
                if (yearlyStats == null) {
                    errors.add("yearlyEmployeeStats[" + i + "] cannot be null");
                    continue;
                }
                if (yearlyStats.employeeId() == null || yearlyStats.employeeId().isBlank()) {
                    errors.add("yearlyEmployeeStats[" + i + "].employee_id is required");
                } else if (!knownEmployeeIds.isEmpty() && !knownEmployeeIds.contains(yearlyStats.employeeId())) {
                    errors.add("yearlyEmployeeStats[" + i + "].employee_id must exist in employees list");
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new ValidationException("Validation failed: " + String.join(", ", errors));
        }

        // 추가 비즈니스 Feasibility 검증
        validateSolverFeasibility(request);
    }

    private static void validateSolverFeasibility(PlanningRequest request) throws ValidationException {
        int totalEmployees = request.employees().size();
        LocalDate startDate = request.organization().firstDraftDate();
        
        // 1. 일자별 요구 시프트 근무 정원 집계
        Map<LocalDate, Integer> dailyRequiredCount = new HashMap<>();
        for (PlanningRequest.RequirementInfo req : request.requirements()) {
            LocalDate targetDate = startDate.plusDays(req.dayIndex());
            dailyRequiredCount.put(targetDate, dailyRequiredCount.getOrDefault(targetDate, 0) + req.employeeCount());
        }

        List<String> feasibilityErrors = new ArrayList<>();

        // 1.7 중복 고정(Locked) 근무 검증
        Map<String, Set<LocalDate>> employeePinnedDates = new HashMap<>();
        if (request.history() != null) {
            for (PlanningRequest.AssignmentInfo assignment : request.history()) {
                if (assignment.isLocked()) {
                    String empId = assignment.employeeId();
                    LocalDate date = assignment.date();
                    
                    employeePinnedDates.computeIfAbsent(empId, k -> new HashSet<>());
                    if (employeePinnedDates.get(empId).contains(date)) {
                        String empName = request.employees().stream()
                                .filter(e -> e.employeeId().equals(empId))
                                .map(PlanningRequest.EmployeeInfo::name)
                                .findFirst()
                                .orElse(empId);
                                
                        feasibilityErrors.add(String.format(
                            "직원 '%s'은 %s에 2개 이상의 근무가 중복 고정(Locked)되어 있습니다. 이중 지정을 해제해 주세요.",
                            empName, date
                        ));
                    }
                    employeePinnedDates.get(empId).add(date);
                }
            }
        }

        // 1.9 휴가와 고정 근무 충돌 검증
        if (request.undesirable() != null) {
            for (PlanningRequest.AssignmentInfo undes : request.undesirable()) {
                String empId = undes.employeeId();
                LocalDate date = undes.date();
                
                if (employeePinnedDates.containsKey(empId) && employeePinnedDates.get(empId).contains(date)) {
                    String empName = request.employees().stream()
                            .filter(e -> e.employeeId().equals(empId))
                            .map(PlanningRequest.EmployeeInfo::name)
                            .findFirst()
                            .orElse(empId);
                            
                    feasibilityErrors.add(String.format(
                        "직원 '%s'은 %s에 비선호/휴가를 신청했으나, 동시에 고정 근무(Locked)가 지정되어 데이터에 모순이 존재합니다.",
                        empName, date
                    ));
                }
            }
        }

        // 2. 필수 스킬 및 시프트 가용성 검증
        // 시프트 ID 별 시프트 정의 정보 매핑 구성
        Map<String, PlanningRequest.ShiftInfo> shiftInfoById = new HashMap<>();
        if (request.organization().shifts() != null) {
            for (PlanningRequest.ShiftInfo shift : request.organization().shifts()) {
                shiftInfoById.put(shift.id(), shift);
            }
        }

        // 일자별/시프트 ID별 요구 정원 집계
        Map<LocalDate, Map<String, Integer>> dailyShiftReqs = new HashMap<>();
        for (PlanningRequest.RequirementInfo req : request.requirements()) {
            LocalDate targetDate = startDate.plusDays(req.dayIndex());
            dailyShiftReqs.computeIfAbsent(targetDate, k -> new HashMap<>())
                          .merge(req.shiftId(), req.employeeCount(), Integer::sum);
        }

        // 일자별/시프트 ID별 가용 직원 수 검증
        for (Map.Entry<LocalDate, Map<String, Integer>> entry : dailyShiftReqs.entrySet()) {
            LocalDate date = entry.getKey();
            for (Map.Entry<String, Integer> shiftEntry : entry.getValue().entrySet()) {
                String shiftId = shiftEntry.getKey();
                int requiredCount = shiftEntry.getValue();

                PlanningRequest.ShiftInfo shift = shiftInfoById.get(shiftId);
                if (shift == null) {
                    continue;
                }

                String skill = shift.code();

                // 해당 날짜에 해당 시프트를 할당받을 수 있는(available_shifts에 포함되어 있거나, skill_set에 해당 스킬 혹은 "ALL"을 가진) 가용 직원 수 집계
                long availableEmployees = request.employees().stream()
                    .filter(emp -> 
                        (emp.availableShifts() != null && emp.availableShifts().contains(shiftId)) ||
                        (emp.availableShifts() != null && emp.availableShifts().contains(skill)) ||
                        (emp.skillSet() != null && (emp.skillSet().contains(skill) || emp.skillSet().contains("ALL")))
                    )
                    .count();

                if (requiredCount > availableEmployees) {
                    feasibilityErrors.add(String.format(
                        "[%s] '%s' 보유 가용 인원(%d명)이 요구되는 전문 근무 정원(%d명)보다 부족합니다.",
                        date, skill, availableEmployees, requiredCount
                    ));
                }
            }
        }

        // 3. 가용 용량 초과 일자 검색 및 예외 처리
        for (Map.Entry<LocalDate, Integer> entry : dailyRequiredCount.entrySet()) {
            LocalDate date = entry.getKey();
            int requiredCount = entry.getValue();
            
            if (requiredCount > totalEmployees) {
                feasibilityErrors.add(String.format(
                    "[%s] 가용한 총 직원 수(%d명)보다 요구되는 근무 정원(%d명)이 더 많아 스케줄을 생성할 수 없습니다.",
                    date, totalEmployees, requiredCount
                ));
            }
        }

        if (!feasibilityErrors.isEmpty()) {
            throw new ValidationException("비즈니스 정합성 모순 발견: " + String.join(" | ", feasibilityErrors));
        }
    }

    /**
     * 유효성 검증 예외
     */
    public static class ValidationException extends Exception {
        public ValidationException(String message) {
            super(message);
        }
    }
}
