package org.acme.api;

import com.google.auth.oauth2.GoogleCredentials;
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
import java.util.Collections;
import java.util.Map;

@Path("/api/trigger")
public class SolverTriggerResource {

    private static final Logger LOG = LoggerFactory.getLogger(SolverTriggerResource.class);

    // 환경 변수나 고정값으로 설정
    private static final String PROJECT_ID = "every-shift-api";
    private static final String REGION = "asia-northeast3";
    private static final String JOB_NAME = "hello-world-job";

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response triggerJob() {
        try {
            LOG.info("Job 실행 요청을 시작합니다.");

            // 1. Google 인증 토큰 생성
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped(Collections.singleton("https://www.googleapis.com/auth/cloud-platform"));
            credentials.refreshIfExpired();
            String token = credentials.getAccessToken().getTokenValue();

            // 2. Cloud Run Job 실행 API 호출
            String url = String.format("https://run.googleapis.com/v2/projects/%s/locations/%s/jobs/%s:run",
                    PROJECT_ID, REGION, JOB_NAME);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
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
