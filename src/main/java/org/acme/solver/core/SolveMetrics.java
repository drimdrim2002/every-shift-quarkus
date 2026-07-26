package org.acme.solver.core;

/** 엔진별 실행 메트릭의 공통 경계입니다. */
public interface SolveMetrics {

    String engineId();

    static SolveMetrics empty() {
        return EmptySolveMetrics.INSTANCE;
    }

    enum EmptySolveMetrics implements SolveMetrics {
        INSTANCE;

        @Override
        public String engineId() {
            return "NONE";
        }
    }
}
