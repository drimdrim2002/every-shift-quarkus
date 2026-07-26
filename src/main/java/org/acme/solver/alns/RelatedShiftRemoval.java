package org.acme.solver.alns;

import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;

import org.acme.solver.core.PlanningProblem;

/** 같은 직원·인접 날짜·같은 교대를 우선하는 관련 shift 제거입니다. */
public final class RelatedShiftRemoval implements DestroyOperator {

    public static final String ID = "RELATED_SHIFT_REMOVAL";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public DestroyPlan destroy(DestroyContext context) {
        DestroyOperatorSupport.validateCapacity(context);
        int[] mutable = context.mutableShiftIndexes();
        int seedShiftIndex = mutable[context.random().nextInt(mutable.length)];
        PlanningProblem.ShiftData seed = context.problem().shifts().get(seedShiftIndex);
        int seedEmployee = context.employeeIndex(seedShiftIndex);
        String seedCode = DestroyContext.normalize(seed.shiftCode());

        Integer[] ranked = Arrays.stream(mutable).boxed().toArray(Integer[]::new);
        Arrays.sort(ranked, Comparator
                .comparingInt((Integer shiftIndex) -> shiftIndex == seedShiftIndex ? 0 : 1)
                .thenComparingInt(shiftIndex -> context.employeeIndex(shiftIndex) == seedEmployee ? 0 : 1)
                .thenComparingLong(shiftIndex -> Math.abs(ChronoUnit.DAYS.between(
                        seed.start().toLocalDate(),
                        context.problem().shifts().get(shiftIndex).start().toLocalDate())))
                .thenComparingInt(shiftIndex -> DestroyContext.normalize(
                        context.problem().shifts().get(shiftIndex).shiftCode()).equals(seedCode) ? 0 : 1)
                .thenComparingInt(Integer::intValue));

        int[] selected = new int[context.requestedRemovalCount()];
        for (int index = 0; index < selected.length; index++) {
            selected[index] = ranked[index];
        }
        return DestroyPlan.of(selected);
    }
}
