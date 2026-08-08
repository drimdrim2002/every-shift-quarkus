package org.acme.solver.alns;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

class FairnessHotspotOperatorTest {

    private static final AlnsIterationConfig ONE_SHIFT_CONFIG =
            new AlnsIterationConfig(1.0d, 1, 1, 1, 2);

    @Test
    void operator_형평성_합계는_full_score_soft2와_같고_최대_한계부담을_제거한다() {
        AlnsTestSupport.Fixture fixture = AlnsTestSupport.fairnessFixture();
        int[] assignments = fixture.initial().employeeIndexByShift();

        long penalty = 0L;
        for (int employeeIndex = 0; employeeIndex < fixture.problem().employeeCount(); employeeIndex++) {
            penalty = Math.addExact(
                    penalty,
                    FairnessOperatorSupport.employeePenalty(
                            fixture.problem(), shiftIndex -> assignments[shiftIndex], employeeIndex));
        }
        assertEquals(-fixture.initial().score().softScore(1), penalty);
        assertEquals(55L, penalty);

        DestroyContext context = new DestroyContext(
                fixture.problem(), assignments, new DestroySize(1, 1), new Random(11L));
        DestroyPlan plan;
        try {
            plan = new FairnessHotspotRemoval().destroy(context);
        } finally {
            context.close();
        }

        assertArrayEquals(new int[] {0}, plan.shiftIndexes());
        assertEquals(
                25L,
                FairnessOperatorSupport.removalGain(
                        fixture.problem(), shiftIndex -> assignments[shiftIndex], 0));
    }

    @Test
    void fairness_aware_repair는_기존_배정수_동률에서_저녁_제곱증분이_작은_직원을_선택한다() {
        AlnsTestSupport.Fixture fixture = AlnsTestSupport.fairnessFixture();
        PartialSolution partial = new PartialSolution(
                fixture.problem().employeeCount(),
                fixture.initial().employeeIndexByShift(),
                new int[] {0});
        RepairContext context = new RepairContext(
                fixture.problem(), partial, new Random(17L), 2);
        RepairResult result;
        try {
            assertEquals(25L, context.fairnessAwareInsertionCost(0, 1));
            assertEquals(15L, context.fairnessAwareInsertionCost(0, 2));
            result = new FairnessAwareRegret2Repair().repair(context);
            assertEquals(2, context.assignment(0));
        } finally {
            context.close();
        }

        assertEquals(RepairResult.completed(1), result);
    }

    @Test
    void 후보_iteration은_full_incremental_검증을_거치고_reject시_완전_rollback한다() {
        AlnsTestSupport.Fixture fixture = AlnsTestSupport.fairnessFixture();
        int[] baselineAssignments = fixture.state().assignments();
        var baselineFingerprint = fixture.state().fingerprint();
        var baselineScore = fixture.state().score();
        var baselineCache = fixture.incremental().cacheFingerprint();

        AlnsIterationResult result = iteration(fixture).execute(
                new FairnessHotspotRemoval(),
                new FairnessAwareRegret2Repair(),
                new AcceptancePolicy() {
                    @Override
                    public String id() {
                        return "ALWAYS_REJECT";
                    }

                    @Override
                    public boolean accept(
                            org.acme.solver.core.RosterScore current,
                            org.acme.solver.core.RosterScore candidate) {
                        return false;
                    }
                },
                ONE_SHIFT_CONFIG,
                new Random(23L),
                () -> false);

        assertEquals(AlnsIterationStatus.REJECTED, result.status());
        assertEquals(1, result.changedAssignmentCount());
        assertEquals(20L, result.candidateScore().softDeltaFrom(baselineScore, 1));
        assertArrayEquals(baselineAssignments, fixture.state().assignments());
        assertEquals(baselineFingerprint, fixture.state().fingerprint());
        assertEquals(baselineScore, fixture.state().score());
        assertEquals(baselineCache, fixture.incremental().cacheFingerprint());
    }

    @Test
    void 같은_seed의_후보_iteration은_assignment와_score가_결정론적이다() {
        AlnsTestSupport.Fixture left = AlnsTestSupport.fairnessFixture();
        AlnsTestSupport.Fixture right = AlnsTestSupport.fairnessFixture();

        AlnsIterationResult leftResult = executeAccepted(left, 29L);
        AlnsIterationResult rightResult = executeAccepted(right, 29L);

        assertEquals(AlnsIterationStatus.ACCEPTED, leftResult.status());
        assertEquals(leftResult, rightResult);
        assertArrayEquals(left.state().assignments(), right.state().assignments());
        assertEquals(left.state().score(), right.state().score());
        assertEquals(
                left.full().calculateScore(left.problem(), left.state().snapshot()),
                left.state().score());
    }

    private static AlnsIterationResult executeAccepted(
            AlnsTestSupport.Fixture fixture, long seed) {
        return iteration(fixture).execute(
                new FairnessHotspotRemoval(),
                new FairnessAwareRegret2Repair(),
                new ImprovementOnlyAcceptance(),
                ONE_SHIFT_CONFIG,
                new Random(seed),
                () -> false);
    }

    private static AlnsIteration iteration(AlnsTestSupport.Fixture fixture) {
        return new AlnsIteration(
                fixture.problem(),
                fixture.state(),
                fixture.incremental(),
                new OperatorCompatibilityMatrix(Map.of(
                        FairnessHotspotRemoval.ID,
                        Set.of(FairnessAwareRegret2Repair.ID))));
    }
}
