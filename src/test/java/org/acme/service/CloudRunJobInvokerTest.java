package org.acme.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;

import org.acme.api.dto.PlanningRequest;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class CloudRunJobInvokerTest {

    @Test
    void buildRunUrlUsesConfiguredRegionWhenProvided() {
        CloudRunJobInvoker invoker = new CloudRunJobInvoker();
        invoker.projectId = "every-shift-api";
        invoker.locationId = "asia-northeast3";
        invoker.configuredRunRegion = Optional.of("us-central1");
        invoker.runJobName = "every-shift-job";
        invoker.runApiBaseUrl = "https://run.googleapis.com";

        String url = invoker.buildRunUrl();

        assertEquals(
                "https://run.googleapis.com/v2/projects/every-shift-api/locations/us-central1/jobs/every-shift-job:run",
                url);
    }

    @Test
    void buildRunRequestBodyUsesExpectedArgOrder() throws Exception {
        CloudRunJobInvoker invoker = new CloudRunJobInvoker();
        invoker.objectMapper = new ObjectMapper();

        String body = invoker.buildRunRequestBody("execution-123", "encoded-payload");
        JsonNode root = invoker.objectMapper.readTree(body);
        JsonNode args = root.path("overrides")
                .path("containerOverrides")
                .get(0)
                .path("args");

        assertEquals("--execution-id", args.get(0).asText());
        assertEquals("execution-123", args.get(1).asText());
        assertEquals("--input-data", args.get(2).asText());
        assertEquals("encoded-payload", args.get(3).asText());
    }

    @Test
    void encodeInputDataCreatesBase64JsonPayload() throws Exception {
        CloudRunJobInvoker invoker = new CloudRunJobInvoker();
        invoker.objectMapper = new ObjectMapper();

        PlanningRequest request = new PlanningRequest(null, null, null, null, null);
        String expectedJson = invoker.objectMapper.writeValueAsString(request);
        String encoded = invoker.encodeInputData(request);
        String decoded = new String(java.util.Base64.getDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8);

        assertEquals(expectedJson, decoded);
    }
}
