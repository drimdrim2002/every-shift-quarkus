package org.acme;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import org.acme.model.EmployeeSchedule;
import org.acme.model.ExecutionStatus;
import org.acme.service.JobExecutionService;
import org.acme.solver.SolverRunner;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@QuarkusMain
public class ApplicationMain implements QuarkusApplication {
    private static final Logger LOG = Logger.getLogger(ApplicationMain.class);

    // 솔버(요리사) 로직을 주입받습니다.
    @Inject
    SolverRunner solverRunner;

    // Firestore 상태 관리를 위한 서비스
    @Inject
    JobExecutionService jobExecutionService;

    @Override
    public int run(String... args) throws Exception {
        // 1. 환경 변수 확인 (기본값: API)
        String appMode = System.getenv("APP_MODE");
        if (appMode == null || appMode.isEmpty()) {
            appMode = "API";
        }

        LOG.info(">>> Application startup mode: " + appMode);

        if ("JOB".equalsIgnoreCase(appMode)) {
            LOG.info(">>> [JOB] Solver started");

            String jsonInput = "{}"; // 기본값
            String executionId = null; // 상태 관리를 위한 execution ID

            // Args 파싱: ["--execution-id", "ID", "--input-data", "BASE64문자열..."]
            // 또는 이전 버전 호환성: ["--input-data", "BASE64문자열..."]
            for (int i = 0; i < args.length; i++) {
                if ("--execution-id".equals(args[i]) && i + 1 < args.length) {
                    executionId = args[i + 1];
                    i++;
                } else if ("--input-data".equals(args[i]) && i + 1 < args.length) {
                    String base64Input = args[i + 1];

                    try {
                        // [핵심] Base64 디코딩
                        byte[] decodedBytes = Base64.getDecoder().decode(base64Input);
                        jsonInput = new String(decodedBytes, StandardCharsets.UTF_8);

                        LOG.info(">>> Data received successfully (length: " + jsonInput.length() + ")");
                    } catch (IllegalArgumentException e) {
                        LOG.error(">>> Base64 decoding failed", e);
                    }
                    i++;
                }
            }

            if (jsonInput.equals("{}")) {
                LOG.warn(">>> No data provided.");
            }

            // 실행 전 상태 업데이트: RUNNING
            if (executionId != null) {
                try {
                    jobExecutionService.updateStatus(executionId, ExecutionStatus.RUNNING);
                    LOG.info(">>> Status updated to RUNNING: " + executionId);
                } catch (Exception e) {
                    LOG.warn(">>> Failed to update status to RUNNING: " + e.getMessage());
                }
            }

            try {
                // 솔버 실행 및 결과 반환
                EmployeeSchedule solution = solverRunner.runWithResult(jsonInput);

                // 결과 저장: COMPLETED
                if (executionId != null) {
                    try {
                        jobExecutionService.saveResult(executionId, solution);
                        LOG.info(">>> Result saved: " + executionId);
                    } catch (Exception e) {
                        LOG.warn(">>> Failed to save result: " + e.getMessage());
                    }
                }

                return 0;

            } catch (Exception e) {
                // 에러 저장: FAILED
                if (executionId != null) {
                    try {
                        jobExecutionService.saveError(executionId, e.getMessage());
                        LOG.info(">>> Error saved: " + executionId);
                    } catch (Exception saveError) {
                        LOG.warn(">>> Failed to save error: " + saveError.getMessage());
                    }
                }
                throw e;
            }

        } else {
            LOG.info(">>> [API] Server waiting...");
            Quarkus.waitForExit();
            return 0;
        }
    }
}
