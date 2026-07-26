package org.acme.solver.alns;

import java.util.Arrays;

/** destroy가 선택한 shift index의 불변 계획입니다. */
public final class DestroyPlan {

    private final int[] shiftIndexes;

    private DestroyPlan(int[] shiftIndexes) {
        this.shiftIndexes = Arrays.copyOf(shiftIndexes, shiftIndexes.length);
        Arrays.sort(this.shiftIndexes);
        for (int index = 0; index < this.shiftIndexes.length; index++) {
            int shiftIndex = this.shiftIndexes[index];
            if (shiftIndex < 0) {
                throw new IllegalArgumentException("shiftIndex는 음수일 수 없습니다: " + shiftIndex);
            }
            if (index > 0 && this.shiftIndexes[index - 1] == shiftIndex) {
                throw new IllegalArgumentException("destroy plan에 중복 shift가 있습니다: " + shiftIndex);
            }
        }
    }

    public static DestroyPlan of(int... shiftIndexes) {
        if (shiftIndexes == null) {
            throw new NullPointerException("shiftIndexes");
        }
        return new DestroyPlan(shiftIndexes);
    }

    public int[] shiftIndexes() {
        return Arrays.copyOf(shiftIndexes, shiftIndexes.length);
    }

    public int actualRemovalCount() {
        return shiftIndexes.length;
    }
}
