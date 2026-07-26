package org.acme.solver.alns;

import java.util.Arrays;
import java.util.random.RandomGenerator;

final class DestroyOperatorSupport {

    private DestroyOperatorSupport() {
    }

    static int[] shuffledMutableShifts(DestroyContext context) {
        int[] mutable = context.mutableShiftIndexes();
        shuffle(mutable, context.random());
        return mutable;
    }

    static void validateCapacity(DestroyContext context) {
        if (context.requestedRemovalCount() < 1) {
            throw new IllegalArgumentException("destroy operator에는 1 이상의 요청 제거 수가 필요합니다.");
        }
        if (context.requestedRemovalCount() > context.mutableShiftIndexes().length) {
            throw new IllegalArgumentException("요청 제거 수가 mutable shift 수보다 큽니다.");
        }
    }

    static void shuffle(int[] values, RandomGenerator random) {
        for (int index = values.length - 1; index > 0; index--) {
            int swapIndex = random.nextInt(index + 1);
            int value = values[index];
            values[index] = values[swapIndex];
            values[swapIndex] = value;
        }
    }

    static int[] first(int[] values, int count) {
        return Arrays.copyOf(values, count);
    }
}
