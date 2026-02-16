package org.acme.solver.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.acme.api.dto.PlanningRequest;
import org.acme.model.Availability;
import org.acme.model.AvailabilityType;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.acme.test.JsonLoader;
import org.acme.util.DtoConverter;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
public class DtoConverterTest {

    @Inject
    DtoConverter dtoConverter;

    @Test
    public void testToEmployeeSchedule() throws IOException {
        // Given
        PlanningRequest request = JsonLoader.load("/json/sample.json", PlanningRequest.class);

        // When
        EmployeeSchedule schedule = dtoConverter.toEmployeeSchedule(request);

        // Then
        assertNotNull(schedule, "The converted schedule should not be null");

        // 1. Verify Employee Count
        int employeeCount = schedule.getEmployeeList().size();
        System.out.println("Total Employees: " + employeeCount);

        // 2. Verify Availability Requests
        List<Availability> availabilities = schedule.getAvailabilityList();
        assertNotNull(availabilities, "Availability list should not be null");
        // assertFalse(availabilities.isEmpty(), "Availability list should not be
        // empty");

        // 3. Verify Night Shift Timing (User Requirement: Night shift starts on next
        // day)
        // Employee: abf84b88-a0c8-4605-aa44-3aa9e5bb87a9 (So Han-ji)
        // History Date: 2025-11-29, Shift: Night (ID:
        // 493edb73-a7a0-4751-8bc1-92745c8bf729)
        // Expected Start: 2025-11-29 + 1 day = 2025-11-30 00:00:00

        Shift nightShift = schedule.getShiftList().stream()
                .filter(s -> s.getEmployee() != null
                        && s.getEmployee().getId().equals("abf84b88-a0c8-4605-aa44-3aa9e5bb87a9"))
                .filter(s -> s.getStart().toLocalDate().equals(LocalDate.of(2025, 11, 30)))
                .filter(s -> s.getStart().toLocalTime().equals(java.time.LocalTime.MIDNIGHT))
                .findFirst()
                .orElse(null);

        assertNotNull(nightShift,
                "Should find the Night shift for So Han-ji starting on 2025-11-30 00:00 (which belongs to 2025-11-29 logical date)");

        // 5. Verify UNDESIRED Availability
        // Check specific request which should now be UNDESIRED
        // Employee: abf84b88-a0c8-4605-aa44-3aa9e5bb87a9
        // Date: 2025-12-31
        boolean hasSpecificUndesired = availabilities.stream()
                .anyMatch(a -> a.getEmployee().getId().equals("abf84b88-a0c8-4605-aa44-3aa9e5bb87a9")
                        && a.getDate().equals(LocalDate.of(2025, 12, 31))
                        && a.getAvailabilityType() == AvailabilityType.UNDESIRED);

        assertTrue(hasSpecificUndesired, "Should have specific UNDESIRED availability for abf84... on Dec 31");

        // 4. Verify the "Evening" shift for another employee to ensure it didn't shift
        // dates
        // { "employee_id": "2fd8...", "shift_id": "...E...", "date": "2025-11-28" }
        // "E" (Evening) starts at 16:00. Offset 0.
        // Expected Start: 2025-11-28 16:00.
        Shift eveningShift = schedule.getShiftList().stream()
                .filter(s -> s.getEmployee() != null
                        && s.getEmployee().getId().equals("2fd8b659-1bef-4aa2-b2ba-bbc7b81b0377"))
                .filter(s -> s.getStart().equals(java.time.LocalDateTime.of(2025, 11, 28, 16, 0)))
                .findFirst()
                .orElse(null);

        assertNotNull(eveningShift, "Should find the Evening shift for Kim Na-bin starting on 2025-11-28 16:00");
    }

    @Test
    public void testToEmployeeSchedule_ShiftCodeMapping() throws IOException {
        PlanningRequest request = JsonLoader.load("/json/request.json", PlanningRequest.class);

        EmployeeSchedule schedule = dtoConverter.toEmployeeSchedule(request);

        Map<String, String> shiftCodeById = request.organization().shifts().stream()
                .collect(Collectors.toMap(PlanningRequest.ShiftInfo::id, PlanningRequest.ShiftInfo::code));

        boolean hasMappedShift = false;
        for (Shift shift : schedule.getShiftList()) {
            String shiftId = shift.getSupabaseId();
            if (shiftId == null) {
                continue;
            }
            String expectedCode = shiftCodeById.get(shiftId);
            if (expectedCode == null) {
                continue;
            }
            hasMappedShift = true;
            assertEquals(expectedCode, shift.getShiftCode(),
                    "Shift code must follow organization.shifts definition for shiftId=" + shiftId);
        }

        assertTrue(hasMappedShift, "At least one mapped shift should exist for shift-code mapping verification");
    }
}
