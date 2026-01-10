package org.acme.solver.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.acme.api.dto.PlanningRequest;
import org.acme.model.Availability;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.acme.util.DtoConverter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class DtoConverterIdTest {

    @Inject
    DtoConverter dtoConverter;

    @Inject
    ObjectMapper objectMapper;

    @Test
    public void testIdsAreGenerated() throws IOException {
        // Given
        PlanningRequest request;
        try (InputStream inputStream = getClass().getResourceAsStream("/json/sample.json")) {
            if (inputStream == null) {
                throw new IOException("Resource not found: /json/sample.json");
            }
            request = objectMapper.readValue(inputStream, PlanningRequest.class);
        }

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
            assertTrue(availabilityIds.add(availability.getId()), "Availability ID must be unique: " + availability.getId());
        }
    }
}
