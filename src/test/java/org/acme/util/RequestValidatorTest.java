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
}
