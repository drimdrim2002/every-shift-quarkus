package org.acme.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.acme.api.dto.PlanningRequest;
import org.junit.jupiter.api.Test;

class RequestValidatorTest {

    @Test
    void validateAllowsMissingFairnessOptionalInputs() throws Exception {
        PlanningRequest request = baseRequest();

        RequestValidator.validate(request);

        assertEquals(List.of(), request.publicHolidays());
        assertEquals(List.of(), request.yearlyEmployeeStats());
    }

    @Test
    void validateRejectsUnknownEmployeeInYearlyStats() {
        PlanningRequest request = new PlanningRequest(
                baseRequest().organization(),
                baseRequest().employees(),
                baseRequest().history(),
                baseRequest().undesirable(),
                baseRequest().requirements(),
                List.of(),
                List.of(new PlanningRequest.YearlyEmployeeStatsInfo("UNKNOWN", 1, 1, 1)));

        RequestValidator.ValidationException exception = assertThrows(
                RequestValidator.ValidationException.class,
                () -> RequestValidator.validate(request));

        assertTrue(exception.getMessage().contains("yearlyEmployeeStats[0].employee_id must exist in employees list"));
    }

    @Test
    void validateRejectsHolidayWithoutDate() {
        PlanningRequest request = new PlanningRequest(
                baseRequest().organization(),
                baseRequest().employees(),
                baseRequest().history(),
                baseRequest().undesirable(),
                baseRequest().requirements(),
                List.of(new PlanningRequest.PublicHolidayInfo(null, "THURSDAY", "목요일", "PUBLIC_HOLIDAY")),
                List.of());

        RequestValidator.ValidationException exception = assertThrows(
                RequestValidator.ValidationException.class,
                () -> RequestValidator.validate(request));

        assertTrue(exception.getMessage().contains("publicHolidays[0].date is required"));
    }

    @Test
    void validateRejectsInsufficientEmployeeCapacity() {
        // Given: 직원은 E1 1명 뿐인데, dayIndex 0에 2명의 시프트(requirements)를 요구함
        PlanningRequest.OrganizationInfo organization = new PlanningRequest.OrganizationInfo(
                "org-1", "테스트 조직", "hospital",
                List.of(new PlanningRequest.ShiftInfo("shift-d", "D", "Day", LocalTime.of(9, 0), LocalTime.of(17, 0))),
                LocalDate.of(2025, 11, 30), 0, LocalDate.of(2025, 12, 1), 5);
        
        PlanningRequest.EmployeeInfo employee = new PlanningRequest.EmployeeInfo("E1", "E1", Set.of("D"), Set.of());
        PlanningRequest.RequirementInfo req1 = new PlanningRequest.RequirementInfo("shift-d", 0, 1);
        PlanningRequest.RequirementInfo req2 = new PlanningRequest.RequirementInfo("shift-d", 0, 1); // 같은날 2명 요구

        PlanningRequest request = new PlanningRequest(
                organization, List.of(employee), List.of(), List.of(), List.of(req1, req2));

        // When & Then: 예외가 터져야 함
        RequestValidator.ValidationException exception = assertThrows(
                RequestValidator.ValidationException.class,
                () -> RequestValidator.validate(request));

        assertTrue(exception.getMessage().contains("가용한 총 직원 수(1명)보다 요구되는 근무 정원(2명)이 더 많아"));
    }

    private PlanningRequest baseRequest() {
        PlanningRequest.OrganizationInfo organization = new PlanningRequest.OrganizationInfo(
                "org-1",
                "테스트 조직",
                "hospital",
                List.of(new PlanningRequest.ShiftInfo(
                        "shift-d",
                        "D",
                        "Day",
                        LocalTime.of(9, 0),
                        LocalTime.of(17, 0))),
                LocalDate.of(2025, 11, 30),
                0,
                LocalDate.of(2025, 12, 1),
                7);
        PlanningRequest.EmployeeInfo employee = new PlanningRequest.EmployeeInfo(
                "E1",
                "E1",
                Set.of("D"),
                Set.of());
        PlanningRequest.RequirementInfo requirement = new PlanningRequest.RequirementInfo("shift-d", 0, 1);

        return new PlanningRequest(
                organization,
                List.of(employee),
                List.of(),
                List.of(),
                List.of(requirement));
    }

    @Test
    void validateRejectsMissingRequiredSkills() {
        // Given: ICU 스킬 근무 1명이 필요한데, 직원은 ICU 스킬이 없는 일반 직원만 있음
        PlanningRequest.OrganizationInfo organization = new PlanningRequest.OrganizationInfo(
                "org-1", "테스트 조직", "hospital",
                List.of(new PlanningRequest.ShiftInfo("shift-icu", "ICU", "ICU Shift", LocalTime.of(9, 0), LocalTime.of(17, 0))),
                LocalDate.of(2025, 11, 30), 0, LocalDate.of(2025, 12, 1), 5);
        
        // E1은 D 스킬만 보유 (ICU 없음)
        PlanningRequest.EmployeeInfo employee = new PlanningRequest.EmployeeInfo("E1", "E1", Set.of("D"), Set.of());
        PlanningRequest.RequirementInfo req = new PlanningRequest.RequirementInfo("shift-icu", 0, 1);

        PlanningRequest request = new PlanningRequest(
                organization, List.of(employee), List.of(), List.of(), List.of(req));

        // When & Then
        RequestValidator.ValidationException exception = assertThrows(
                RequestValidator.ValidationException.class,
                () -> RequestValidator.validate(request));

        assertTrue(exception.getMessage().contains("보유 가용 인원(0명)이 요구되는 전문 근무 정원(1명)보다 부족합니다."));
    }

    @Test
    void validateRejectsDoublePinnedShifts() {
        // Given: E1 직원이 2025-12-01에 Day 시프트와 Night 시프트 둘 다 Locked(Pinned)되어 입력됨
        PlanningRequest.OrganizationInfo organization = new PlanningRequest.OrganizationInfo(
                "org-1", "테스트 조직", "hospital",
                List.of(new PlanningRequest.ShiftInfo("shift-d", "D", "Day", LocalTime.of(9, 0), LocalTime.of(17, 0))),
                LocalDate.of(2025, 11, 30), 0, LocalDate.of(2025, 12, 1), 5);

        PlanningRequest.EmployeeInfo employee = new PlanningRequest.EmployeeInfo("E1", "E1", Set.of("D"), Set.of());
        
        // 동일날짜(2025-12-01)에 2개의 Locked 배치
        PlanningRequest.AssignmentInfo locked1 = new PlanningRequest.AssignmentInfo("E1", "shift-d", LocalDate.of(2025, 12, 1), true);
        PlanningRequest.AssignmentInfo locked2 = new PlanningRequest.AssignmentInfo("E1", "shift-d", LocalDate.of(2025, 12, 1), true);

        PlanningRequest request = new PlanningRequest(
                organization, List.of(employee), List.of(locked1, locked2), List.of(), List.of(new PlanningRequest.RequirementInfo("shift-d", 0, 1)));

        // When & Then
        RequestValidator.ValidationException exception = assertThrows(
                RequestValidator.ValidationException.class,
                () -> RequestValidator.validate(request));

        assertTrue(exception.getMessage().contains("2개 이상의 근무가 중복 고정(Locked)되어 있습니다."));
    }
}
