package org.acme.solver.alns;

/** q = clamp(round(n * rate), qMin, min(qMax, n)) 규칙을 구현합니다. */
public final class DestroySizePolicy {

    public DestroySize calculate(
            int mutableShiftCount,
            double destroyRate,
            int qMin,
            int qMax,
            int absoluteRemovalLimit) {
        if (mutableShiftCount < 0) {
            throw new IllegalArgumentException("mutableShiftCount는 음수일 수 없습니다.");
        }
        if (!Double.isFinite(destroyRate) || destroyRate < 0.0d) {
            throw new IllegalArgumentException("destroyRate는 유한한 0 이상 값이어야 합니다.");
        }
        if (qMin < 1 || qMax < qMin) {
            throw new IllegalArgumentException("1 <= qMin <= qMax여야 합니다.");
        }
        if (absoluteRemovalLimit < qMax) {
            throw new IllegalArgumentException("actual removal 절대 상한은 qMax 이상이어야 합니다.");
        }
        if (mutableShiftCount == 0) {
            return new DestroySize(0, 0);
        }

        int upper = Math.min(qMax, mutableShiftCount);
        int lower = Math.min(qMin, upper);
        long rounded = Math.round(mutableShiftCount * destroyRate);
        int requested = (int) Math.max(lower, Math.min((long) upper, rounded));
        int actualLimit = Math.min(absoluteRemovalLimit, mutableShiftCount);
        return new DestroySize(Math.max(1, requested), actualLimit);
    }
}
