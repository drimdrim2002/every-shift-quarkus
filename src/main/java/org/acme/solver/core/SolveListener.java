package org.acme.solver.core;

/**
 * 검증된 complete best solution을 전달받는 callback입니다.
 *
 * <p>listener가 던진 예외는 엔진이 탐색 상태와 분리해 처리해야 합니다.</p>
 */
@FunctionalInterface
public interface SolveListener {

    SolveListener NOOP = solution -> {
    };

    void onBestSolution(RosterSolution solution);

    static SolveListener noop() {
        return NOOP;
    }
}
