package org.acme.solver.alns;

/** repair 종료 상태입니다. partial assignment 자체는 포함하지 않습니다. */
public record RepairResult(boolean complete, int attempts) {

    public RepairResult {
        if (attempts < 0) {
            throw new IllegalArgumentException("repair attempts는 음수일 수 없습니다: " + attempts);
        }
    }

    public static RepairResult completed(int attempts) {
        return new RepairResult(true, attempts);
    }

    public static RepairResult failed(int attempts) {
        return new RepairResult(false, attempts);
    }
}
