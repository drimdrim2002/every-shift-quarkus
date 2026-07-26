package org.acme.solver.alns;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class BaselineOperatorDeterminismTest {

    @Test
    void 세_destroy는_같은_seed에서_같은_plan을_만들고_immutable을_제외한다() {
        AlnsTestSupport.Fixture fixture = AlnsTestSupport.relationFixture();
        for (DestroyOperator operator : List.of(
                new RandomRemoval(),
                new RelatedShiftRemoval(),
                new PreceptorRelationGroupRemoval())) {
            int[] first = destroy(operator, fixture, new DestroySize(2, 4), 20260715L);
            int[] second = destroy(operator, fixture, new DestroySize(2, 4), 20260715L);

            assertArrayEquals(first, second, operator.id());
            assertTrue(first.length >= 2 && first.length <= 4, operator.id());
            for (int shiftIndex : first) {
                assertTrue(fixture.problem().isMutableShift(shiftIndex), operator.id());
            }
            assertFalse(Arrays.stream(first).anyMatch(index -> index == 0 || index == 8), operator.id());
        }
    }

    @Test
    void relation_group은_q를_원자확장하고_actual_상한을_넘으면_다른_bundle을_선택한다() {
        AlnsTestSupport.Fixture fixture = AlnsTestSupport.relationFixture();
        PreceptorRelationGroupRemoval operator = new PreceptorRelationGroupRemoval();

        int[] expanded = destroy(operator, fixture, new DestroySize(1, 2), 7L);
        int[] capped = destroy(operator, fixture, new DestroySize(1, 1), 7L);

        assertEquals(2, expanded.length, "relation group bundle은 q=1을 넘어 원자 확장되어야 합니다.");
        assertEquals(1, capped.length, "확장 bundle이 상한을 넘으면 허용 가능한 bundle을 다시 선택해야 합니다.");
    }

    @Test
    void 세_repair는_같은_partial과_seed에서_complete_assignment를_재현한다() {
        AlnsTestSupport.Fixture fixture = AlnsTestSupport.relationFixture();
        int[] removed = { 1, 2, 3, 4 };

        for (RepairOperator operator : List.of(
                new GreedyRepair(), new Regret2Repair(), new RelationAwareRepair())) {
            int[] first = repair(operator, fixture, removed, 42L);
            int[] second = repair(operator, fixture, removed, 42L);

            assertArrayEquals(first, second, operator.id());
            assertTrue(Arrays.stream(first).allMatch(employee -> employee >= 0), operator.id());
        }
    }

    private static int[] destroy(
            DestroyOperator operator,
            AlnsTestSupport.Fixture fixture,
            DestroySize size,
            long seed) {
        DestroyContext context = new DestroyContext(
                fixture.problem(), fixture.initial().employeeIndexByShift(), size, new Random(seed));
        try {
            return operator.destroy(context).shiftIndexes();
        } finally {
            context.close();
        }
    }

    private static int[] repair(
            RepairOperator operator,
            AlnsTestSupport.Fixture fixture,
            int[] removed,
            long seed) {
        PartialSolution partial = new PartialSolution(
                fixture.problem().employeeCount(),
                fixture.initial().employeeIndexByShift(),
                removed);
        RepairContext context = new RepairContext(
                fixture.problem(), partial, new Random(seed), 3);
        try {
            RepairResult result = operator.repair(context);
            assertTrue(result.complete(), operator.id());
            assertTrue(context.isComplete(), operator.id());
            return context.completeAssignments();
        } finally {
            context.close();
        }
    }
}
