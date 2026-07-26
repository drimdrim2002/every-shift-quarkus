package org.acme.solver.core;

/**
 * 엔진 구현과 무관한 solve 종료 이유입니다.
 */
public enum TerminationReason {
    CONTINUE(false),
    COMPLETED(true),
    CONVERGED(true),
    DEADLINE_REACHED(true),
    MAX_ITERATIONS_REACHED(true),
    MAX_EVALUATIONS_REACHED(true),
    CANCELLED(true),
    INITIAL_SOLUTION_FAILED(true),
    NO_FEASIBLE_SOLUTION(true),
    SCORE_MISMATCH(true),
    STATE_CORRUPTION(true);

    private final boolean terminal;

    TerminationReason(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
