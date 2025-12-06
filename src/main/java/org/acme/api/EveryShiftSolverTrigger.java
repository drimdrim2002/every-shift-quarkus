package org.acme.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

@Path("/api/solve")
public class EveryShiftSolverTrigger {

    private static final Logger LOG = LoggerFactory.getLogger(EveryShiftSolverTrigger.class);

    // 환경 변수나 고정값으로 설정
    private static final String PROJECT_ID = "every-shift-api";
    private static final String REGION = "asia-northeast3";
    private static final String JOB_NAME = "hello-world-job";

    @Inject
    ObjectMapper objectMapper; // JSON 변환을 위해 Jackson ObjectMapper 주입

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response triggerJob(Map<String, Object> payload) {
        try {
            LOG.info("Job 실행 요청을 시작합니다. 파라미터33: " + payload);

            // 1. 입력받은 JSON Body를 String으로 변환 (Cloud Run 환경변수로 넣기 위함)
            String inputJsonString = objectMapper.writeValueAsString(payload);

            // 2. Base64 인코딩 (특수문자 문제 해결)
            String safePayload = Base64.getEncoder().encodeToString(inputJsonString.getBytes());

            // 3. Google 인증 토큰 생성
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped(Collections.singleton("https://www.googleapis.com/auth/cloud-platform"));
            credentials.refreshIfExpired();
            String token = credentials.getAccessToken().getTokenValue();

            // 5. Job 실행 요청 Body 구성 (Args로 전달)
            // 인자 1: --input-data
            // 인자 2: (Base64로 된 엄청 긴 문자열)
            List<String> argsList = List.of("--input-data", safePayload);

            Map<String, Object> containerOverride = Map.of("args", argsList);
            Map<String, Object> overrides = Map.of("containerOverrides", List.of(containerOverride));
            Map<String, Object> googleApiBody = Map.of("overrides", overrides);

            String requestBody = objectMapper.writeValueAsString(googleApiBody);

            // 2. Cloud Run Job 실행 API 호출
            String url = String.format("https://run.googleapis.com/v2/projects/%s/locations/%s/jobs/%s:run",
                    PROJECT_ID, REGION, JOB_NAME);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> apiResponse = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (apiResponse.statusCode() == 200 || apiResponse.statusCode() == 202) {
                LOG.info("Job 실행 요청 성공: " + apiResponse.body());
                return Response.ok("Job Started: " + apiResponse.body()).build();
            } else {
                LOG.error("Job 실행 요청 실패: " + apiResponse.statusCode() + " / " + apiResponse.body());
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("Failed to start job: " + apiResponse.body()).build();
            }

        } catch (IOException | InterruptedException e) {
            LOG.error("API 호출 중 오류 발생", e);
            return Response.serverError().entity(e.getMessage()).build();
        }
    }


}
