package org.acme.solver.alns;

/** 각 미배정 shift의 최소 지역 삽입 비용 직원을 순서대로 선택합니다. */
public final class GreedyRepair implements RepairOperator {

    public static final String ID = "GREEDY_REPAIR";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RepairResult repair(RepairContext context) {
        while (context.beginAttempt()) {
            if (RepairOperatorSupport.fillGreedy(context)) {
                return RepairResult.completed(context.attempts());
            }
        }
        return RepairResult.failed(context.attempts());
    }
}
