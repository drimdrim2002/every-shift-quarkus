package org.acme.solver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class SolverRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SolverRunner.class);

    @Inject
    ObjectMapper objectMapper; // JSON 파싱 도구
    public void run(String jsonInput) {
        // --- 여기에 선생님의 메타휴리스틱 알고리즘을 넣으세요 ---

        LOG.info("--- 솔버 계산 시작 ---");

        // 예: OR-Tools 실행, 최적화 루프 등
        try {
            // 1. JSON 문자열을 JsonNode 트리로 변환 (구조 탐색 용이)
            JsonNode rootNode = objectMapper.readTree(jsonInput);
            // 2. 데이터 꺼내 쓰기 (예시)
            String hospitalName = rootNode.get("organization").get("name").asText();
            int employeeCount = rootNode.get("employees").size();

            LOG.info(">>> 병원 이름: " + hospitalName);
            LOG.info(">>> 직원 수: " + employeeCount + "명");
            // 테스트용: 3초간 계산하는 척
            Thread.sleep(3000);
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }

        LOG.info("--- 솔버 계산 종료 ---");
    }
}
