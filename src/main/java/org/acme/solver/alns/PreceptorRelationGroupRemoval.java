package org.acme.solver.alns;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** relation group을 실제일·교대 bundle로 원자 확장해 제거합니다. */
public final class PreceptorRelationGroupRemoval implements DestroyOperator {

    public static final String ID = "PRECEPTOR_RELATION_GROUP_REMOVAL";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public DestroyPlan destroy(DestroyContext context) {
        DestroyOperatorSupport.validateCapacity(context);
        int[] shuffled = DestroyOperatorSupport.shuffledMutableShifts(context);
        List<int[]> grouped = new ArrayList<>();
        List<int[]> singleton = new ArrayList<>();
        Set<String> seenBundles = new LinkedHashSet<>();

        for (int seedShiftIndex : shuffled) {
            int[] bundle = context.relationBundle(seedShiftIndex);
            Arrays.sort(bundle);
            String key = Arrays.toString(bundle);
            if (!seenBundles.add(key)) {
                continue;
            }
            if (bundle.length > 1) {
                grouped.add(bundle);
            } else {
                singleton.add(bundle);
            }
        }

        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        if (addUntilRequested(context, grouped, selected)
                || addUntilRequested(context, singleton, selected)) {
            return DestroyPlan.of(selected.stream().mapToInt(Integer::intValue).toArray());
        }
        throw new IllegalStateException(
                "relation group 확장 뒤 actual removal 상한 안에서 요청 제거 수를 충족할 수 없습니다.");
    }

    private static boolean addUntilRequested(
            DestroyContext context,
            List<int[]> bundles,
            LinkedHashSet<Integer> selected) {
        for (int[] bundle : bundles) {
            LinkedHashSet<Integer> expanded = new LinkedHashSet<>(selected);
            for (int shiftIndex : bundle) {
                expanded.add(shiftIndex);
            }
            if (expanded.size() > context.actualRemovalLimit()) {
                continue;
            }
            selected.clear();
            selected.addAll(expanded);
            if (selected.size() >= context.requestedRemovalCount()) {
                return true;
            }
        }
        return selected.size() >= context.requestedRemovalCount();
    }
}
