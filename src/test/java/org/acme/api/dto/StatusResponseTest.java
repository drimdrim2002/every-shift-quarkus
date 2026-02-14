package org.acme.api.dto;

import java.time.Instant;

import org.acme.model.ExecutionStatus;
import org.acme.model.JobExecution;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class StatusResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testFrom_ParsesResultJsonCorrectly() throws Exception {
        // Given
        JobExecution job = new JobExecution();
        job.setId("test-id");
        job.setStatus(ExecutionStatus.COMPLETED);
        job.setCreatedAt(Instant.now().toEpochMilli());

        String resultJson = "{\"key\": \"value\", \"number\": 123}";
        job.setResultJson(resultJson);

        // When
        StatusResponse response = StatusResponse.from(job, objectMapper);

        // Then
        Assertions.assertNotNull(response.result());
        Assertions.assertEquals("value", response.result().get("key"));
        Assertions.assertEquals(123, response.result().get("number"));
    }

    @Test
    void testFrom_HandlesNullResultJson() {
        // Given
        JobExecution job = new JobExecution();
        job.setId("test-id");
        job.setStatus(ExecutionStatus.COMPLETED);
        job.setResultJson(null);

        // When
        StatusResponse response = StatusResponse.from(job, objectMapper);

        // Then
        Assertions.assertNull(response.result());
    }

    @Test
    void testFrom_HandlesInvalidJson() {
        // Given
        JobExecution job = new JobExecution();
        job.setId("test-id");
        job.setStatus(ExecutionStatus.COMPLETED);
        job.setResultJson("{invalid-json");

        // When
        StatusResponse response = StatusResponse.from(job, objectMapper);

        // Then
        Assertions.assertNull(response.result());
    }

    @Test
    void testFrom_MapsBendableScoreFields() {
        JobExecution job = new JobExecution();
        job.setId("test-id");
        job.setStatus(ExecutionStatus.COMPLETED);
        job.setHardScore(0);
        job.setUndesiredSoftScore(-120);
        job.setFairSoftScore(-5400);
        job.setDesiredSoftScore(240);

        StatusResponse response = StatusResponse.from(job, objectMapper);

        Assertions.assertNotNull(response.score());
        Assertions.assertEquals(0, response.score().hardScore());
        Assertions.assertEquals(-120, response.score().undesiredSoftScore());
        Assertions.assertEquals(-5400, response.score().fairSoftScore());
        Assertions.assertEquals(240, response.score().desiredSoftScore());
        Assertions.assertNull(response.score().legacySoftScoreTotal());
    }

    @Test
    void testFrom_MapsLegacySoftScore() {
        JobExecution job = new JobExecution();
        job.setId("test-id");
        job.setStatus(ExecutionStatus.COMPLETED);
        job.setHardScore(0);
        job.setSoftScore(-6121);

        StatusResponse response = StatusResponse.from(job, objectMapper);

        Assertions.assertNotNull(response.score());
        Assertions.assertEquals(0, response.score().hardScore());
        Assertions.assertNull(response.score().undesiredSoftScore());
        Assertions.assertNull(response.score().fairSoftScore());
        Assertions.assertNull(response.score().desiredSoftScore());
        Assertions.assertEquals(-6121, response.score().legacySoftScoreTotal());
    }
}
