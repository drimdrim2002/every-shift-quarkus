package org.acme.solver.alns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** destroy/repair ID의 정적 허용 조합입니다. */
public final class OperatorCompatibilityMatrix {

    private final Map<String, Set<String>> compatibleRepairIdsByDestroyId;

    public OperatorCompatibilityMatrix(Map<String, Set<String>> compatibleRepairIdsByDestroyId) {
        Objects.requireNonNull(compatibleRepairIdsByDestroyId, "compatibleRepairIdsByDestroyId");
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        compatibleRepairIdsByDestroyId.forEach((destroyId, repairIds) -> {
            requireId(destroyId, "destroyId");
            Objects.requireNonNull(repairIds, "repairIds");
            LinkedHashSet<String> stable = new LinkedHashSet<>();
            for (String repairId : repairIds) {
                requireId(repairId, "repairId");
                stable.add(repairId);
            }
            if (stable.isEmpty()) {
                throw new IllegalArgumentException("destroy operator에는 하나 이상의 compatible repair가 필요합니다: " + destroyId);
            }
            copy.put(destroyId, Collections.unmodifiableSet(stable));
        });
        this.compatibleRepairIdsByDestroyId = Collections.unmodifiableMap(copy);
    }

    public static OperatorCompatibilityMatrix baseline() {
        Map<String, Set<String>> compatibility = new LinkedHashMap<>();
        Set<String> generalRepairs = new LinkedHashSet<>(List.of(
                GreedyRepair.ID, Regret2Repair.ID, RelationAwareRepair.ID));
        compatibility.put(RandomRemoval.ID, generalRepairs);
        compatibility.put(RelatedShiftRemoval.ID, generalRepairs);
        compatibility.put(PreceptorRelationGroupRemoval.ID, Set.of(RelationAwareRepair.ID));
        return new OperatorCompatibilityMatrix(compatibility);
    }

    public boolean isCompatible(DestroyOperator destroy, RepairOperator repair) {
        Objects.requireNonNull(destroy, "destroy");
        Objects.requireNonNull(repair, "repair");
        return compatibleRepairIdsByDestroyId
                .getOrDefault(destroy.id(), Set.of())
                .contains(repair.id());
    }

    public void requireCompatible(DestroyOperator destroy, RepairOperator repair) {
        if (!isCompatible(destroy, repair)) {
            throw new IllegalArgumentException(
                    "호환되지 않는 ALNS operator 조합입니다: " + destroy.id() + " -> " + repair.id());
        }
    }

    public List<RepairOperator> compatibleRepairs(
            DestroyOperator destroy, List<? extends RepairOperator> repairs) {
        Objects.requireNonNull(destroy, "destroy");
        Objects.requireNonNull(repairs, "repairs");
        List<RepairOperator> result = new ArrayList<>();
        for (RepairOperator repair : repairs) {
            if (isCompatible(destroy, repair)) {
                result.add(repair);
            }
        }
        return List.copyOf(result);
    }

    public Map<String, Set<String>> asMap() {
        return compatibleRepairIdsByDestroyId;
    }

    private static void requireId(String id, String field) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(field + "는 비어 있을 수 없습니다.");
        }
    }
}
