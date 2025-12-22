package org.acme.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Quarkus Native Build를 위해 @RegisterForReflection 추가
 * record는 getter, toString, equals 등을 자동 생성함
 */
@RegisterForReflection
public record PlanningRequest(
        OrganizationInfo organization,
        List<ShiftInfo> shifts,
        List<EmployeeInfo> employees,
        List<AssignmentInfo> assignments,

        // 날짜별 요구 근무량 (Key: LocalDate 자동 매핑됨)
        // Value: "D": 3, "E": 4 같은 동적 키를 위해 Map<String, Integer> 사용
        Map<LocalDate, Map<String, Integer>> requirements
) {

    // 1. 조직 도메인
    @RegisterForReflection
    public record OrganizationInfo(
            Long id,
            String name,
            String type,

            @JsonFormat(pattern = "yyyy-MM-dd") // "2025-11-29" 형태 파싱 명시
            LocalDate lastHistoricalDate,

            int publishLength,

            @JsonFormat(pattern = "yyyy-MM-dd") // "2025-11-29" 형태 파싱 명시
            LocalDate firstDraftDate,

            int draftLength

    ) {}

    // 2. 근무(Shift) 도메인
    @RegisterForReflection
    public record ShiftInfo(
            String code,
            String name,

            @JsonProperty("start_time")
            @JsonFormat(pattern = "HH:mm:ss") // "08:00:00" 형태 파싱 명시
            LocalTime startTime,

            @JsonProperty("end_time")
            @JsonFormat(pattern = "HH:mm:ss")
            LocalTime endTime
    ) {}

    // 3. 직원(Employee) 도메인
    @RegisterForReflection
    public record EmployeeInfo(
            @JsonProperty("employee_id")
            String employeeId,

            String name,

            @JsonProperty("available_shifts")
            List<String> availableShifts
    ) {}

    // 4. 기배정(Assignment) 도메인 - 이미 확정된 근무
    @RegisterForReflection
    public record AssignmentInfo(
            @JsonProperty("employee_id")
            String employeeId,

            @JsonProperty("shift_id")
            String shiftId,

            @JsonFormat(pattern = "yyyy-MM-dd") // "2025-11-29" 형태 파싱 명시
            LocalDate date,

            @JsonProperty("is_locked")
            boolean isLocked
    ) {}
}