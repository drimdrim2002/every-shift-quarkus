package org.acme.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.acme.model.EmployeeSchedule;
import org.acme.test.JsonLoader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Cloud Run 원격 실행 스냅샷을 로컬에서 재현하는 테스트.
 *
 * <p>대상 executionId: {@code 3e56517c-2682-4ee2-a89f-310a3813b983}
 * (Cloud Run Job execution: {@code every-shift-job-wz7cb}, 2026-08-08)
 *
 * <p>실행 예:
 * <pre>
 * ./mvnw -Dtest=RemoteExecutionReplayTest test
 * ./scripts/run-remote-replay.sh
 * </pre>
 *
 * <p>결과 JSON은 dev/test 모드에서 {@code target/schedule-output/} 로 export 된다.
 */
@QuarkusTest
@Tag("remote-replay")
public class RemoteExecutionReplayTest {

    private static final String INPUT_RESOURCE =
            "/json/remote/3e56517c-2682-4ee2-a89f-310a3813b983.json";

    /** 원격 실행에서 관측된 hard score (재현 시 최소한 이 수준을 기대). */
    private static final int REMOTE_HARD_SCORE = 0;

    @Inject
    SolverRunner solverRunner;

    @Test
    public void replayRemoteExecution_3e56517c() throws IOException {
        String jsonInput = JsonLoader.loadAsString(INPUT_RESOURCE);
        assertNotNull(jsonInput);
        assertTrue(jsonInput.contains("세브란스병원"), "input should contain remote organization");

        EmployeeSchedule solution = solverRunner.runWithResult(jsonInput);

        assertNotNull(solution, "solution should not be null");
        assertNotNull(solution.getScore(), "score should not be null");
        assertEquals(REMOTE_HARD_SCORE, solution.getScore().hardScore(),
                "hard constraints should be feasible (remote baseline was 0 hard)");

        // soft 점수는 시드/시간 예산에 따라 달라질 수 있어 값 고정 검증은 하지 않는다.
        // 원격 관측값: [0]hard/[-480/0/-6301/0]soft
    }
}
