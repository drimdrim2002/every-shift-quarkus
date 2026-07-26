package org.acme.solver.alns;

/** 두 번째 후보를 잃는 비용이 가장 큰 shift부터 복구합니다. */
public final class Regret2Repair implements RepairOperator {

    public static final String ID = "REGRET_2_REPAIR";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RepairResult repair(RepairContext context) {
        while (context.beginAttempt()) {
            boolean failed = false;
            while (!context.isComplete()) {
                RepairOperatorSupport.Choice choice = RepairOperatorSupport.highestRegret2(context);
                if (choice == null) {
                    failed = true;
                    break;
                }
                int employeeIndex = RepairOperatorSupport.randomBestTie(
                        context, choice.shiftIndex(), choice.rankedCandidates());
                context.assign(choice.shiftIndex(), employeeIndex);
            }
            if (!failed && context.isComplete()) {
                return RepairResult.completed(context.attempts());
            }
        }
        return RepairResult.failed(context.attempts());
    }
}
