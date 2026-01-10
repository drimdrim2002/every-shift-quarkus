package org.acme.solver.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.acme.api.dto.PlanningRequest;
import org.acme.model.Availability;
import org.acme.model.AvailabilityType;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.acme.util.DtoConverter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class DtoConverterTest {

    @Inject
    DtoConverter dtoConverter;

    @Inject
    ObjectMapper objectMapper;

    @Test
    public void testToEmployeeSchedule() throws IOException {
        // Given
        PlanningRequest request;
        try (InputStream inputStream = getClass().getResourceAsStream("/json/sample.json")) {
            if (inputStream == null) {
                throw new IOException("Resource not found: /json/sample.json");
            }
            request = objectMapper.readValue(inputStream, PlanningRequest.class);
        }

        // When
        EmployeeSchedule schedule = dtoConverter.toEmployeeSchedule(request);

        // Then
        assertNotNull(schedule, "The converted schedule should not be null");

        // 1. Verify Employee Count
        int employeeCount = schedule.getEmployeeList().size();
        System.out.println("Total Employees: " + employeeCount);

        // 2. Verify Historic Gap Filling (Nov 27 - Nov 30)
        LocalDate[] historicDates = {
            LocalDate.of(2025, 11, 27),
            LocalDate.of(2025, 11, 28),
            LocalDate.of(2025, 11, 29),
            LocalDate.of(2025, 11, 30)
        };

        for (LocalDate date : historicDates) {
            long shiftsOnDate = schedule.getShiftList().stream()
                    .filter(s -> s.getStart().toLocalDate().equals(date))
                    .count();
            
            System.out.println("Date " + date + ": " + shiftsOnDate + " shifts (Expected: " + employeeCount + ")");
            assertEquals(employeeCount, shiftsOnDate, "Mismatch in shift count for historic date " + date);
            
            // Verify all shifts on historic dates are pinned
            boolean allPinned = schedule.getShiftList().stream()
                    .filter(s -> s.getStart().toLocalDate().equals(date))
                    .allMatch(Shift::isPinned);
            assertTrue(allPinned, "All shifts on " + date + " must be pinned.");
        }

        // 3. Verify Availability Requests (Dec 1 onwards)
        // Check availability list is populated
        List<Availability> availabilities = schedule.getAvailabilityList();
        assertNotNull(availabilities, "Availability list should not be null");
        assertFalse(availabilities.isEmpty(), "Availability list should not be empty");
        
        System.out.println("Total Availabilities: " + availabilities.size());

        // Check for specific known requests from JSON
        // Example: employee "3515886c..." (Go So-young) requested "Holiday" (not Off) on 2025-12-05 -> DESIRED
        // Wait, JSON says: employee 3515... shift "ce7d..." (Holiday code "H") on 2025-12-05
        
        // Let's verify counts of UNAVAILABLE vs DESIRED if possible, or just check existence.
        long desiredCount = availabilities.stream().filter(a -> a.getAvailabilityType() == AvailabilityType.DESIRED).count();
        long unavailableCount = availabilities.stream().filter(a -> a.getAvailabilityType() == AvailabilityType.UNAVAILABLE).count();
        
        System.out.println("DESIRED count: " + desiredCount);
        System.out.println("UNAVAILABLE count: " + unavailableCount);
        
        assertTrue(desiredCount > 0, "Should have DESIRED availabilities");
        // Check if there are any UNAVAILABLE? 
        // In the JSON provided in previous step, I see mostly "ce7d..." (Holiday), "ce7d..." (Holiday).
        // Is there any "Off" request in "requests" list?
        // Let's check the request list in sample.json logic I wrote.
        // It seems most are "ce7d..." (Holiday) or other shifts.
        // If there are no OFF requests in the sample, unavailableCount might be 0.
        // That is acceptable as long as the logic is correct.
        
        // Let's check specifically for one DESIRED entry
        // Employee: abf84b88-a0c8-4605-aa44-3aa9e5bb87a9
        // Date: 2025-12-31
        // Shift: ce7d25fd-3f2f-4267-9075-1bf658359052 (Holiday) -> Not "Off" -> DESIRED
        boolean hasSpecificDesired = availabilities.stream()
                .anyMatch(a -> a.getEmployee().getId().equals("abf84b88-a0c8-4605-aa44-3aa9e5bb87a9")
                        && a.getDate().equals(LocalDate.of(2025, 12, 31))
                        && a.getAvailabilityType() == AvailabilityType.DESIRED);
        
        assertTrue(hasSpecificDesired, "Should have specific DESIRED availability for abf84... on Dec 31");
    }
}