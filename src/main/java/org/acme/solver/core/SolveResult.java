package org.acme.solver.core;

import java.util.Objects;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * 솔버 엔진 공통 결과 계약입니다.
 *
 * @param bestSolution 엔진이 반환한 최선해
 * @param score 검증된 최선해의 점수
 * @param terminationReason 종료 이유
 * @param iterations 수행한 iteration 수
 * @param evaluationCount 수행한 후보 평가 수
 * @param elapsedMillis 실행시간(ms)
 * @param seed 실행에 사용한 random seed
 * @param metrics 엔진별 실행 메트릭
 */
@RegisterForReflection
public record SolveResult<S>(
        S bestSolution,
        RosterScore score,
        TerminationReason terminationReason,
        long iterations,
        long evaluationCount,
        long elapsedMillis,
        long seed,
        SolveMetrics metrics) {

    public SolveResult(
            S bestSolution,
            RosterScore score,
            TerminationReason terminationReason,
            long iterations,
            long evaluationCount,
            long elapsedMillis,
            long seed) {
        this(bestSolution, score, terminationReason, iterations, evaluationCount,
                elapsedMillis, seed, SolveMetrics.empty());
    }

    public SolveResult {
        Objects.requireNonNull(terminationReason, "terminationReason");
        Objects.requireNonNull(metrics, "metrics");
        if (!terminationReason.isTerminal()) {
            throw new IllegalArgumentException("SolveResult에는 종료 상태만 사용할 수 있습니다: " + terminationReason);
        }
        if ((bestSolution == null) != (score == null)) {
            throw new IllegalArgumentException("bestSolution과 score는 함께 존재하거나 함께 없어야 합니다.");
        }
        if (iterations < 0) {
            throw new IllegalArgumentException("iterations는 음수일 수 없습니다: " + iterations);
        }
        if (evaluationCount < 0) {
            throw new IllegalArgumentException("evaluationCount는 음수일 수 없습니다: " + evaluationCount);
        }
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis는 음수일 수 없습니다: " + elapsedMillis);
        }
    }
}
