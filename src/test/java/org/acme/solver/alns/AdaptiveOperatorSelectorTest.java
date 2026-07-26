package org.acme.solver.alns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AdaptiveOperatorSelectorTest {

    @Test
    void rewardSum_useCount_평균과_rho_공식으로_destroy_repair를_독립_갱신한다() {
        StubDestroy d0 = new StubDestroy("d0");
        StubDestroy d1 = new StubDestroy("d1");
        StubRepair r0 = new StubRepair("r0");
        StubRepair r1 = new StubRepair("r1");
        AdaptiveOperatorSelector selector = selector(
                List.of(d0, d1), List.of(r0, r1),
                config(10.0d, 0.5d, 10, 6.0d, 2.0d, 1.0d, 0.0d),
                7L);

        selector.recordOutcome(
                new AdaptiveOperatorSelector.Selection(0, d0, 0, r0),
                OperatorOutcome.GLOBAL_BEST);
        selector.recordOutcome(
                new AdaptiveOperatorSelector.Selection(0, d0, 1, r1),
                OperatorOutcome.CURRENT_IMPROVEMENT);
        selector.finishSegment();

        assertEquals(7.0d, selector.destroyWeight("d0")); // .5*10 + .5*((6+2)/2)
        assertEquals(10.0d, selector.destroyWeight("d1")); // useCount == 0
        assertEquals(8.0d, selector.repairWeight("r0")); // .5*10 + .5*6
        assertEquals(6.0d, selector.repairWeight("r1")); // .5*10 + .5*2
    }

    @Test
    void useCount_0인_operator는_기존_weight를_유지한다() {
        List<StubDestroy> destroys = destroys("d0", "d1");
        List<StubRepair> repairs = repairs("r0", "r1");
        AdaptiveOperatorSelector selector = selector(
                destroys, repairs,
                config(3.0d, 1.0d, 1, 10.0d, 5.0d, 1.0d, 0.0d),
                1L);
        AdaptiveOperatorSelector.Selection selected = new AdaptiveOperatorSelector.Selection(
                0, destroys.get(0), 0, repairs.get(0));

        selector.recordOutcome(selected, OperatorOutcome.GLOBAL_BEST);

        assertEquals(10.0d, selector.destroyWeight("d0"));
        assertEquals(3.0d, selector.destroyWeight("d1"));
        assertEquals(10.0d, selector.repairWeight("r0"));
        assertEquals(3.0d, selector.repairWeight("r1"));
    }

    @Test
    void reward_0과_rho_극단에서도_weight는_wMin_아래나_0_NaN으로_가지_않는다() {
        StubDestroy destroy = new StubDestroy("d0");
        StubRepair repair = new StubRepair("r0");
        AdaptiveOperatorSelector selector = selector(
                List.of(destroy), List.of(repair),
                config(1.0d, 1.0d, 1, 4.0d, 2.0d, 1.0d, 0.0d),
                1L);

        selector.recordOutcome(
                new AdaptiveOperatorSelector.Selection(0, destroy, 0, repair),
                OperatorOutcome.REJECTED);

        assertEquals(0.1d, selector.destroyWeight("d0"));
        assertEquals(0.1d, selector.repairWeight("r0"));
        assertTrue(Double.isFinite(selector.destroyWeight("d0")));
        assertTrue(Double.isFinite(selector.repairWeight("r0")));
    }

    @Test
    void 고보상_operator가_있어도_다른_operator의_weight와_선택확률은_양수다() {
        StubDestroy d0 = new StubDestroy("d0");
        StubDestroy d1 = new StubDestroy("d1");
        StubRepair r0 = new StubRepair("r0");
        StubRepair r1 = new StubRepair("r1");
        AdaptiveOperatorSelector selector = selector(
                List.of(d0, d1), List.of(r0, r1),
                config(1.0d, 1.0d, 1, 1_000_000.0d, 2.0d, 1.0d, 0.0d),
                1L);

        selector.recordOutcome(
                new AdaptiveOperatorSelector.Selection(0, d0, 0, r0),
                OperatorOutcome.GLOBAL_BEST);

        assertTrue(selector.destroyWeight("d1") > 0.0d);
        assertTrue(selector.repairWeight("r1") > 0.0d);
        assertTrue(selector.destroySelectionProbability("d1") > 0.0d);
        assertTrue(selector.repairSelectionProbability("d0", "r1") > 0.0d);
    }

    @Test
    void 동일_seed와_안정_index는_같은_선택열을_재현하고_compatibility를_지킨다() {
        List<StubDestroy> destroys = destroys("d0", "d1", "d2");
        List<StubRepair> repairs = repairs("r0", "r1", "r2");
        Map<String, Set<String>> allowed = new LinkedHashMap<>();
        allowed.put("d0", Set.of("r0", "r2"));
        allowed.put("d1", Set.of("r1"));
        allowed.put("d2", Set.of("r0", "r1", "r2"));
        OperatorCompatibilityMatrix matrix = new OperatorCompatibilityMatrix(allowed);
        AdaptiveOperatorConfig config = config(1.0d, 0.2d, 100, 10.0d, 5.0d, 1.0d, 0.0d);
        AdaptiveOperatorSelector first = new AdaptiveOperatorSelector(
                destroys, repairs, matrix, config, new Random(42L));
        AdaptiveOperatorSelector second = new AdaptiveOperatorSelector(
                destroys, repairs, matrix, config, new Random(42L));
        List<String> firstSequence = new ArrayList<>();
        List<String> secondSequence = new ArrayList<>();

        for (int index = 0; index < 100; index++) {
            AdaptiveOperatorSelector.Selection a = first.select();
            AdaptiveOperatorSelector.Selection b = second.select();
            firstSequence.add(a.destroyOperator().id() + ":" + a.repairOperator().id());
            secondSequence.add(b.destroyOperator().id() + ":" + b.repairOperator().id());
            assertTrue(matrix.isCompatible(a.destroyOperator(), a.repairOperator()));
        }

        assertEquals(firstSequence, secondSequence);
        assertTrue(firstSequence.stream().distinct().count() > 1L);
    }

    @Test
    void 비음수_reward_wMin_rho와_NaN_무한대를_검증한다() {
        assertThrows(IllegalArgumentException.class,
                () -> config(1.0d, -0.1d, 10, 1, 1, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveOperatorConfig(1, 0.1, 0.1, 10, 1, 1, -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveOperatorConfig(1, 0.1, 0.1, 10, Double.NaN, 1, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveOperatorConfig(1, 0.1, 0.1, 10, Double.POSITIVE_INFINITY, 1, 1, 0));
    }

    @Test
    void rewardSum이_무한대가_되면_즉시_차단한다() {
        StubDestroy destroy = new StubDestroy("d0");
        StubRepair repair = new StubRepair("r0");
        AdaptiveOperatorSelector selector = selector(
                List.of(destroy), List.of(repair),
                new AdaptiveOperatorConfig(1, 0.1, 0.5, 3, Double.MAX_VALUE, 1, 1, 0),
                1L);
        AdaptiveOperatorSelector.Selection selection =
                new AdaptiveOperatorSelector.Selection(0, destroy, 0, repair);

        selector.recordOutcome(selection, OperatorOutcome.GLOBAL_BEST);
        assertThrows(IllegalStateException.class,
                () -> selector.recordOutcome(selection, OperatorOutcome.GLOBAL_BEST));
    }

    private static List<StubDestroy> destroys(String... ids) {
        return java.util.Arrays.stream(ids).map(StubDestroy::new).toList();
    }

    private static List<StubRepair> repairs(String... ids) {
        return java.util.Arrays.stream(ids).map(StubRepair::new).toList();
    }

    private static AdaptiveOperatorSelector selector(
            List<? extends DestroyOperator> destroys,
            List<? extends RepairOperator> repairs,
            AdaptiveOperatorConfig config,
            long seed) {
        Map<String, Set<String>> compatibility = new LinkedHashMap<>();
        Set<String> repairIds = repairs.stream().map(RepairOperator::id)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        for (DestroyOperator destroy : destroys) {
            compatibility.put(destroy.id(), repairIds);
        }
        return new AdaptiveOperatorSelector(
                destroys,
                repairs,
                new OperatorCompatibilityMatrix(compatibility),
                config,
                new Random(seed));
    }

    private static AdaptiveOperatorConfig config(
            double initialWeight,
            double rho,
            int segmentLength,
            double global,
            double current,
            double worse,
            double rejected) {
        return new AdaptiveOperatorConfig(
                initialWeight, 0.1d, rho, segmentLength,
                global, current, worse, rejected);
    }

    private record StubDestroy(String id) implements DestroyOperator {
        @Override
        public DestroyPlan destroy(DestroyContext context) {
            throw new UnsupportedOperationException();
        }
    }

    private record StubRepair(String id) implements RepairOperator {
        @Override
        public RepairResult repair(RepairContext context) {
            throw new UnsupportedOperationException();
        }
    }
}
