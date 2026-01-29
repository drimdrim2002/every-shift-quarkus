package org.acme;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
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

            // Args 파싱: ["--input-data", "BASE64문자열..."]
            if (args.length >= 2 && "--input-data".equals(args[0])) {
                String base64Input = args[1];

                try {
                    // [핵심] Base64 디코딩
                    byte[] decodedBytes = Base64.getDecoder().decode(base64Input);
                    jsonInput = new String(decodedBytes, StandardCharsets.UTF_8);

                    LOG.info(">>> Data received successfully (length: " + jsonInput.length() + ")");
                } catch (IllegalArgumentException e) {
                    LOG.error(">>> Base64 decoding failed", e);
                }
            } else {
                LOG.warn(">>> No data provided.");
            }

            // 복원된 JSON을 솔버에게 전달
            solverRunner.run(jsonInput);

            return 0;
        } else {
            LOG.info(">>> [API] Server waiting...");
            Quarkus.waitForExit();
            return 0;
        }
    }
}
