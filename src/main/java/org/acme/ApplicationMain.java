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

        LOG.info(">>> 애플리케이션 시작 모드: " + appMode);

        if ("JOB".equalsIgnoreCase(appMode)) {
            LOG.info(">>> [JOB] 솔버 시작");

            String jsonInput = "{}"; // 기본값

            // Args 파싱: ["--input-data", "BASE64문자열..."]
            if (args.length >= 2 && "--input-data".equals(args[0])) {
                String base64Input = args[1];

                try {
                    // [핵심] Base64 디코딩
                    byte[] decodedBytes = Base64.getDecoder().decode(base64Input);
                    jsonInput = new String(decodedBytes, StandardCharsets.UTF_8);

                    LOG.info(">>> 데이터 수신 성공 (길이: " + jsonInput.length() + ")");
                } catch (IllegalArgumentException e) {
                    LOG.error(">>> Base64 디코딩 실패", e);
                }
            } else {
                LOG.warn(">>> 전달된 데이터가 없습니다.");
            }

            // 복원된 JSON을 솔버에게 전달
            solverRunner.run(jsonInput);

            return 0;
        } else {
            LOG.info(">>> [API] 서버 대기 중...");
            Quarkus.waitForExit();
            return 0;
        }
    }
}
