package org.acme.api;

import java.nio.charset.StandardCharsets;

import org.acme.api.dto.PlanningRequest;
import org.acme.api.dto.SolveResponse;
import org.acme.resource.WorkerResource;
import org.acme.service.JobExecutionService;
import org.acme.util.RequestValidator;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.HttpRequest;
import com.google.cloud.tasks.v2.OidcToken;
import com.google.cloud.tasks.v2.QueueName;
import com.google.cloud.tasks.v2.Task;
import com.google.protobuf.ByteString;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * 비동기 Solver 실행 API
 * POST /api/solve 요청을 받아서 executionId를 반환하고 실제 solver는 비동기로 실행
 */
@Path("/api/solve")
public class SolverResource {

    private static final Logger LOG = LoggerFactory.getLogger(SolverResource.class);

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "gcp.project-id")
    String projectId;

    @ConfigProperty(name = "gcp.location-id")
    String locationId;

    @ConfigProperty(name = "gcp.queue-id")
    String queueId;

    @ConfigProperty(name = "gcp.worker-url")
    String workerUrl;

    @ConfigProperty(name = "gcp.service-account-email")
    String serviceAccountEmail;

    @ConfigProperty(name = "app.solver.run-locally", defaultValue = "false")
    boolean runLocally;

    @Inject
    WorkerResource workerResource;

    @Inject
    ManagedExecutor managedExecutor;

    @Inject
    JobExecutionService jobExecutionService;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response triggerJob(PlanningRequest requestDto) {
        try {
            // 1. Input Validation
            RequestValidator.validate(requestDto);

            // 2. Firestore에 실행 문서 생성 (PENDING)
            String executionId = jobExecutionService.create(requestDto);
            LOG.info("JobExecution created with id: {}", executionId);

            // 3. 비동기 실행
            if (runLocally) {
                // 로컬 비동기 실행
                managedExecutor.execute(() -> {
                    try {
                        workerResource.processEngineTask(executionId, requestDto);
                    } catch (Exception e) {
                        LOG.error("Local solver execution failed: executionId={}", executionId, e);
                        jobExecutionService.saveError(executionId, e.getMessage());
                    }
                });
            } else {
                // Cloud Tasks에 executionId를 Header로 전송
                createCloudTask(requestDto, executionId);
            }

            // 4. ID 반환
            return Response.ok(SolveResponse.created(executionId)).build();

        } catch (RequestValidator.ValidationException e) {
            LOG.warn("Validation failed: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new org.acme.api.dto.ErrorResponse(e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.error("Failed to trigger job", e);
            return Response.serverError()
                    .entity(new org.acme.api.dto.ErrorResponse("Failed to trigger job: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Cloud Tasks에 Task 생성
     */
    private void createCloudTask(PlanningRequest requestDto, String executionId) {
        try (CloudTasksClient client = CloudTasksClient.create()) {

            // 1. Queue 경로 설정
            String queuePath = QueueName.of(projectId, locationId, queueId).toString();

            // 2. DTO -> JSON String 직렬화
            String jsonPayload = objectMapper.writeValueAsString(requestDto);
            LOG.info("Creating Cloud Task with executionId: {}", executionId);

            // 3. Task 생성 (HTTP Request 형태 정의)
            Task.Builder taskBuilder = Task.newBuilder()
                    .setHttpRequest(HttpRequest.newBuilder()
                            .setBody(ByteString.copyFrom(jsonPayload, StandardCharsets.UTF_8))
                            .setUrl(workerUrl)
                            .setHttpMethod(HttpMethod.POST)
                            .putHeaders("Content-Type", "application/json")
                            // executionId를 헤더로 전달
                            .putHeaders("X-Execution-Id", executionId)
                            // Cloud Run 간 인증을 위해 OIDC 토큰 필수
                            .setOidcToken(OidcToken.newBuilder()
                                    .setServiceAccountEmail(serviceAccountEmail)
                                    .build())
                            .build());

            // 4. 큐에 전송
            client.createTask(queuePath, taskBuilder.build());

            LOG.info("Cloud Task created successfully: executionId={}", executionId);

        } catch (Exception e) {
            LOG.error("Failed to create Cloud Task: executionId={}", executionId, e);
            // Task 생성 실패 시 FAILED 상태로 저장
            jobExecutionService.saveError(executionId, "Failed to create Cloud Task: " + e.getMessage());
            throw new RuntimeException("Failed to create Cloud Task", e);
        }
    }
}
