package org.acme.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.tasks.v2.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.protobuf.ByteString;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.acme.api.dto.PlanningRequest;
import org.acme.resource.WorkerResource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

@Path("/api/solve")
public class EveryShiftSolverTrigger {

    private static final Logger LOG = LoggerFactory.getLogger(EveryShiftSolverTrigger.class);

    // 환경 변수나 고정값으로 설정
    private static final String PROJECT_ID = "every-shift-api";
    private static final String REGION = "asia-northeast3";
    private static final String JOB_NAME = "hello-world-job";

    @Inject
    ObjectMapper objectMapper; // JSON 변환을 위해 Jackson ObjectMapper 주입

    // application.properties에서 값을 주입받음
    @ConfigProperty(name = "gcp.project-id")
    String projectId;

    @ConfigProperty(name = "gcp.location-id")
    String locationId;

    @ConfigProperty(name = "gcp.queue-id")
    String queueId;

    @ConfigProperty(name = "gcp.worker-url")
    String workerUrl; // https://.../worker/process

    @ConfigProperty(name = "gcp.service-account-email")
    String serviceAccountEmail;

    @ConfigProperty(name = "app.solver.run-locally", defaultValue = "false")
    boolean runLocally;

    @Inject
    WorkerResource workerResource;

    @Inject
    ManagedExecutor managedExecutor;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response triggerJob(PlanningRequest requestDto) {

        if (runLocally) {
            LOG.info("Running solver locally (Async)...");
            managedExecutor.execute(() -> {
                try {
                    workerResource.processEngineTask(requestDto);
                } catch (Exception e) {
                    LOG.error("Local solver execution failed", e);
                }
            });

            JsonObject responseObject = new JsonObject();
            responseObject.addProperty("message", "Task submitted successfully (Local Async)11");
            return Response.ok(new Gson().toJson(responseObject) ).build();
        }

        try (CloudTasksClient client = CloudTasksClient.create()){

            // 1. Queue 경로 설정/
            String queuePath = QueueName.of(projectId, locationId, queueId).toString();


            // 2. DTO -> JSON String 직렬화
            String jsonPayload = objectMapper.writeValueAsString(requestDto);
            LOG.info(jsonPayload);

            // 3. Task 생성 (HTTP Request 형태 정의)
            Task.Builder taskBuilder = Task.newBuilder()
                    .setHttpRequest(HttpRequest.newBuilder()
                            .setBody(ByteString.copyFrom(jsonPayload, StandardCharsets.UTF_8))
                            .setUrl(workerUrl)
                            .setHttpMethod(HttpMethod.POST)
                            .putHeaders("Content-Type", "application/json")
                            // Cloud Run 간 인증을 위해 OIDC 토큰 필수
                            .setOidcToken(OidcToken.newBuilder()
                                    .setServiceAccountEmail(serviceAccountEmail)
                                    .build())
                            .build());

            // 4. 큐에 전송
            client.createTask(queuePath, taskBuilder.build());


            String message = "Task submitted successfully";
            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty("message", message);

            return Response.ok(jsonObject).build();

        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            return Response.serverError().entity("Failed to create task: " + e.getMessage()).build();
        }
    }


}
