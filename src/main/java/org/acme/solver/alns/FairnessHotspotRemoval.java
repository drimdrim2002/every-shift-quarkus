package org.acme.solver.alns;

import java.util.Arrays;
import java.util.Comparator;

/**
 * 현재 soft[2] 제곱 부담의 한계 감소가 큰 mutable assignment부터 제거합니다.
 *
 * <p>employee-level penalty removal을 현재 q 상한에 맞춘 bounded hotspot 형태로 사용합니다.</p>
 */
public final class FairnessHotspotRemoval implements DestroyOperator {

    public static final String ID = "FAIRNESS_HOTSPOT_REMOVAL";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public DestroyPlan destroy(DestroyContext context) {
        DestroyOperatorSupport.validateCapacity(context);
        FairnessOperatorSupport.Snapshot fairness = FairnessOperatorSupport.snapshot(
                context.problem(), context::employeeIndex);
        Integer[] ranked = Arrays.stream(context.mutableShiftIndexes())
                .boxed()
                .toArray(Integer[]::new);
        Arrays.sort(ranked, Comparator
                .comparingLong((Integer shiftIndex) -> fairness.removalGain(shiftIndex))
                .reversed()
                .thenComparing(Comparator
                        .comparingLong((Integer shiftIndex) ->
                                fairness.employeePenalty(context.employeeIndex(shiftIndex)))
                        .reversed())
                .thenComparingInt(Integer::intValue));

        int[] selected = new int[context.requestedRemovalCount()];
        for (int index = 0; index < selected.length; index++) {
            selected[index] = ranked[index];
        }
        return DestroyPlan.of(selected);
    }
}
