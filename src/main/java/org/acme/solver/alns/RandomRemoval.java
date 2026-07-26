package org.acme.solver.alns;

/** seed 기반 무작위 표본으로 mutable assignment를 제거합니다. */
public final class RandomRemoval implements DestroyOperator {

    public static final String ID = "RANDOM_REMOVAL";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public DestroyPlan destroy(DestroyContext context) {
        DestroyOperatorSupport.validateCapacity(context);
        int[] shuffled = DestroyOperatorSupport.shuffledMutableShifts(context);
        return DestroyPlan.of(DestroyOperatorSupport.first(shuffled, context.requestedRemovalCount()));
    }
}
