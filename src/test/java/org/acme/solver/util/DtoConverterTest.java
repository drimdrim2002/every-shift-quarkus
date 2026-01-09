package org.acme.solver.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.acme.api.dto.PlanningRequest;
import org.acme.solver.model.EmployeeSchedule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

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
    }
}
