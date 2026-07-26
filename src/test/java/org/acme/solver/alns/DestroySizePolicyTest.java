package org.acme.solver.alns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DestroySizePolicyTest {

    private final DestroySizePolicy policy = new DestroySizePolicy();

    @Test
    void q는_round_rate_qMin_qMax_mutable수를_모두_적용한다() {
        assertEquals(new DestroySize(8, 20), policy.calculate(100, 0.10d, 2, 8, 20));
        assertEquals(new DestroySize(2, 20), policy.calculate(100, 0.001d, 2, 8, 20));
    }

    @Test
    void 작은_입력에서도_n이_양수면_q는_1이상이고_n을_넘지_않는다() {
        assertEquals(new DestroySize(3, 3), policy.calculate(3, 0.01d, 5, 10, 10));
        assertEquals(new DestroySize(1, 1), policy.calculate(1, 0.0d, 1, 5, 5));
        assertEquals(new DestroySize(0, 0), policy.calculate(0, 0.5d, 1, 5, 5));
    }

    @Test
    void 잘못된_상한과_rate는_즉시_거절한다() {
        assertThrows(IllegalArgumentException.class,
                () -> policy.calculate(10, Double.NaN, 1, 5, 5));
        assertThrows(IllegalArgumentException.class,
                () -> policy.calculate(10, 0.2d, 0, 5, 5));
        assertThrows(IllegalArgumentException.class,
                () -> policy.calculate(10, 0.2d, 1, 5, 4));
    }
}
