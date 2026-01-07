package org.acme.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Quarkus Native Build를 위해 @RegisterForReflection 추가
 * record는 getter, toString, equals 등을 자동 생성함
 */
@RegisterForReflection
public record PlanningRequest(
        OrganizationInfo organization,
        List<EmployeeInfo> employees,
        List<AssignmentInfo> assignments,

        // 기존 Map<LocalDate, Map<String, Integer>> 구조에서 확장성 있는 List 구조로 변경
        Map<String, Map<String, Integer>> requirements
) {

    // 1. 조직 도메인
    @RegisterForReflection
    public record OrganizationInfo(
            String id,
            String name,
            String type,
            List<ShiftInfo> shifts,

            @JsonFormat(pattern = "yyyy-MM-dd")
            LocalDate lastHistoricalDate,

            int publishLength,

            @JsonFormat(pattern = "yyyy-MM-dd")
            LocalDate firstDraftDate,

            int draftLength

    ) {
    }

    // 2. 근무(Shift) 도메인
    @RegisterForReflection
    public record ShiftInfo(
            String id,
            String code,
            String name,

            @JsonProperty("start_time")
            @JsonFormat(pattern = "HH:mm:ss")
            LocalTime startTime,

            @JsonProperty("end_time")
            @JsonFormat(pattern = "HH:mm:ss")
            LocalTime endTime
    ) {
    }

    // 3. 직원(Employee) 도메인
    @RegisterForReflection
    public record EmployeeInfo(
            @JsonProperty("employee_id")
            String employeeId,

            String name,

            @JsonProperty("available_shifts")
            Set<String> availableShifts,

            @JsonProperty("skill_set")
            Set<String> skillSet
    ) {
        public EmployeeInfo {
            if (name == null) {
                name = employeeId;
            }
            if (skillSet == null) {
                skillSet = new HashSet<>();
            }

            if (availableShifts == null) {
                availableShifts = new HashSet<>();
            }
        }
    }

    // 4. 기배정(Assignment) 도메인
    @RegisterForReflection
    public record AssignmentInfo(
            @JsonProperty("employee_id")
            String employeeId,

            @JsonProperty("shift_id")
            String shiftId,

            @JsonFormat(pattern = "yyyy-MM-dd")
            LocalDate date,

            @JsonProperty("is_locked")
            boolean isLocked
    ) {
    }
}
