package org.acme.solver.util;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.acme.api.dto.PlanningRequest;
import org.acme.model.Availability;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.acme.test.JsonLoader;
import org.acme.util.DtoConverter;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class DtoConverterIdTest {

    @Test
    public void testIdsAreGenerated() throws IOException {
        // Given
        PlanningRequest request = JsonLoader.load("/json/sample.json", PlanningRequest.class);

        // When
        EmployeeSchedule schedule = DtoConverter.toEmployeeSchedule(request);

        // Then
        assertNotNull(schedule);

        Set<Long> shiftIds = new HashSet<>();
        for (Shift shift : schedule.getShiftList()) {
            assertNotNull(shift.getId(), "Shift ID must not be null: " + shift);
            assertTrue(shiftIds.add(shift.getId()), "Shift ID must be unique: " + shift.getId());
        }

        Set<Long> availabilityIds = new HashSet<>();
        for (Availability availability : schedule.getAvailabilityList()) {
            assertNotNull(availability.getId(), "Availability ID must not be null: " + availability);
            assertTrue(availabilityIds.add(availability.getId()),
                    "Availability ID must be unique: " + availability.getId());
        }
    }
}
