package org.acme.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.acme.api.dto.PlanningRequest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class CloudRunJobInvoker {

    private static final Logger LOG = LoggerFactory.getLogger(CloudRunJobInvoker.class);
    private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "gcp.project-id")
    String projectId;

    @ConfigProperty(name = "gcp.location-id")
    String locationId;

    @Inject
    @ConfigProperty(name = "gcp.run.region")
    Optional<String> configuredRunRegion;

    @ConfigProperty(name = "gcp.run.job-name")
    String runJobName;

    @ConfigProperty(name = "gcp.run.api-base-url", defaultValue = "https://run.googleapis.com")
    String runApiBaseUrl;

    String buildRunUrl() {
        String region = configuredRunRegion
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElse(locationId);

        return String.format("%s/v2/projects/%s/locations/%s/jobs/%s:run",
                runApiBaseUrl, projectId, region, runJobName);
    }

    String buildRunRequestBody(String executionId, String encodedInput) throws Exception {
        List<String> args = List.of("--execution-id", executionId, "--input-data", encodedInput);
        Map<String, Object> payload = Map.of(
                "overrides", Map.of(
                        "containerOverrides", List.of(
                                Map.of("args", args))));

        return objectMapper.writeValueAsString(payload);
    }

    String encodeInputData(PlanningRequest requestDto) throws Exception {
        String jsonPayload = objectMapper.writeValueAsString(requestDto);
        return Base64.getEncoder().encodeToString(jsonPayload.getBytes(StandardCharsets.UTF_8));
    }

    public String runJob(String executionId, PlanningRequest requestDto) {
        try {
            String encodedInput = encodeInputData(requestDto);
            String requestBody = buildRunRequestBody(executionId, encodedInput);
            String runUrl = buildRunUrl();
            String token = obtainAccessToken();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(runUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                if (statusCode == 401 || statusCode == 403) {
                    LOG.error(
                            "Cloud Run Jobs invocation permission error. Check service account permissions: run.jobs.run/run.jobs.runWithOverrides");
                }
                throw new IllegalStateException(
                        "Cloud Run Jobs API call failed: status=" + statusCode + ", body=" + response.body());
            }

            JsonNode responseBody = objectMapper.readTree(response.body());
            String executionResourceName = responseBody.path("name").asText("(unknown)");

            LOG.info("Cloud Run Job dispatched: executionId={}, runExecutionName={}", executionId,
                    executionResourceName);
            return executionResourceName;
        } catch (Exception e) {
            throw new RuntimeException("Failed to run Cloud Run Job: " + e.getMessage(), e);
        }
    }

    String obtainAccessToken() throws Exception {
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault().createScoped(CLOUD_PLATFORM_SCOPE);
        credentials.refreshIfExpired();

        AccessToken accessToken = credentials.getAccessToken();
        if (accessToken == null || accessToken.getTokenValue() == null || accessToken.getTokenValue().isBlank()) {
            credentials.refresh();
            accessToken = credentials.getAccessToken();
        }

        if (accessToken == null || accessToken.getTokenValue() == null || accessToken.getTokenValue().isBlank()) {
            throw new IllegalStateException("Failed to obtain access token for Cloud Run Jobs API");
        }

        return accessToken.getTokenValue();
    }
}
