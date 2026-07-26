package org.acme.solver.alns;

/** Phase 6A 한 iteration의 파괴·복구 안전 한계입니다. */
public record AlnsIterationConfig(
        double destroyRate,
        int qMin,
        int qMax,
        int absoluteRemovalLimit,
        int maxRepairAttempts) {

    public AlnsIterationConfig {
        if (!Double.isFinite(destroyRate) || destroyRate < 0.0d) {
            throw new IllegalArgumentException("destroyRate는 유한한 0 이상 값이어야 합니다.");
        }
        if (qMin < 1 || qMax < qMin) {
            throw new IllegalArgumentException("1 <= qMin <= qMax여야 합니다.");
        }
        if (absoluteRemovalLimit < qMax) {
            throw new IllegalArgumentException("absoluteRemovalLimit는 qMax 이상이어야 합니다.");
        }
        if (maxRepairAttempts < 1) {
            throw new IllegalArgumentException("maxRepairAttempts는 1 이상이어야 합니다.");
        }
    }
}
