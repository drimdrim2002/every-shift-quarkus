package org.acme.solver.alns;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import org.acme.solver.core.RosterScore;
import org.acme.solver.move.SolutionFingerprint;
import org.junit.jupiter.api.Test;

class LexicographicSaAcceptanceTest {

    private static final SaAcceptanceConfig CONFIG = new SaAcceptanceConfig(0.2d, 0.01d, 100L);

    @Test
    void hard_개선은_즉시_수락하고_feasible_도달_후_hard_lock을_건다() {
        LexicographicSaAcceptance acceptance = acceptance(fallback(), () -> 0.999d, false);

        assertTrue(acceptance.accept(score(-2, 0, 0, 0, 0), score(-1, -100, 0, 0, 0)));
        assertTrue(acceptance.accept(score(-1, 0, 0, 0, 0), score(0, -1_000, 0, 0, 0)));
        assertTrue(acceptance.feasibleRegionLocked());
        assertFalse(acceptance.accept(score(0, 0, 0, 0, 0), score(-1, 1_000_000, 0, 0, 0)));
        assertEquals(
                LexicographicSaAcceptance.DecisionType.FEASIBLE_REGION_LOCK,
                acceptance.lastDecision().type());
    }

    @Test
    void infeasible_구간_hard_악화는_hardScale_기반_별도_SA만_사용한다() {
        SaCalibrationResult calibration = calibration(10.0d, new double[] { 1, 1, 1, 1 });
        LexicographicSaAcceptance accepted = acceptance(calibration, () -> 0.19d, false);
        LexicographicSaAcceptance rejected = acceptance(calibration, () -> 0.21d, false);
        RosterScore current = score(-1, -100, 0, 0, 0);
        RosterScore candidate = score(-11, 1_000_000, 0, 0, 0);

        assertTrue(accepted.accept(current, candidate));
        assertFalse(rejected.accept(current, candidate));
        assertEquals(10L, rejected.lastDecision().loss());
        assertEquals(1.0d, rejected.lastDecision().energy());
        assertEquals(0.2d, rejected.lastDecision().probability(), 1.0e-12);
    }

    @Test
    void hard가_같을_때만_첫_soft_차이를_보고_하위_soft_대폭_개선으로_상쇄하지_않는다() {
        SaCalibrationResult calibration = calibration(1.0d, new double[] { 1, 1, 1, 1 });
        LexicographicSaAcceptance acceptance = acceptance(calibration, () -> 0.99d, true);
        RosterScore current = score(0, 0, -10, -10, -10);
        RosterScore candidate = score(0, -1, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

        assertFalse(acceptance.accept(current, candidate));
        assertEquals(LexicographicSaAcceptance.DecisionType.SOFT_WORSENING_SA,
                acceptance.lastDecision().type());
        assertEquals(0, acceptance.lastDecision().softLevel());
        assertEquals(1L, acceptance.lastDecision().loss());
    }

    @Test
    void 첫_soft_level_개선은_낮은_level_손실과_무관하게_즉시_수락한다() {
        LexicographicSaAcceptance acceptance = acceptance(fallback(), () -> 0.999d, true);

        assertTrue(acceptance.accept(
                score(0, 0, 100, 100, 100),
                score(0, 1, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE)));
        assertEquals(0, acceptance.lastDecision().softLevel());
    }

    @Test
    void delta는_long으로_계산되어_int_overflow가_없다() {
        SaCalibrationResult calibration = calibration(
                1.0d,
                new double[] { 1.0d, 1.0d, 1.0d, 1.0d });
        LexicographicSaAcceptance acceptance = acceptance(calibration, () -> 0.0d, true);

        acceptance.accept(
                score(0, Integer.MAX_VALUE, 0, 0, 0),
                score(0, Integer.MIN_VALUE, 0, 0, 0));

        assertEquals(4_294_967_295L, acceptance.lastDecision().loss());
        assertTrue(Double.isFinite(acceptance.lastDecision().energy()));
    }

    @Test
    void 확률_경계와_평가횟수_진행률_cooling을_지킨다() {
        SaCalibrationResult calibration = calibration(1.0d, new double[] { 1, 1, 1, 1 });
        LexicographicSaAcceptance below = acceptance(calibration, () -> 0.199999d, true);
        LexicographicSaAcceptance above = acceptance(calibration, () -> 0.200001d, true);
        RosterScore current = score(0, 0, 0, 0, 0);
        RosterScore candidate = score(0, -1, 0, 0, 0);

        assertTrue(below.accept(current, candidate));
        assertFalse(above.accept(current, candidate));
        assertEquals(CONFIG.initialTemperature(), below.temperatureAt(0), 1.0e-12);
        assertEquals(CONFIG.initialTemperature() * CONFIG.finalTemperatureRatio(),
                below.temperatureAt(99), 1.0e-12);
        assertEquals(below.temperatureAt(99), below.temperatureAt(1000), 1.0e-12);
    }

    @Test
    void p0와_scale_유효성을_검증하고_scale_표본이_없으면_1을_쓴다() {
        assertThrows(IllegalArgumentException.class,
                () -> new SaAcceptanceConfig(0.0d, 0.1d, 10L));
        assertThrows(IllegalArgumentException.class,
                () -> new SaAcceptanceConfig(1.0d, 0.1d, 10L));
        assertThrows(IllegalArgumentException.class,
                () -> calibration(Double.NaN, new double[] { 1, 1, 1, 1 }));
        assertEquals(1.0d, SaCalibrator.medianOrOne(List.of()));
        assertEquals(4.0d, SaCalibrator.medianOrOne(List.of(9L, 2L, 4L)));
    }

    @Test
    void calibration은_파생_seed와_state_copy를_사용해_원본과_search_RNG를_소비하지_않는다() {
        AlnsTestSupport.Fixture fixture = AlnsTestSupport.skillFixture(true);
        int[] assignments = fixture.state().assignments();
        SolutionFingerprint fingerprint = fixture.state().fingerprint();
        long seed = 42L;
        SplittableRandom untouchedSearchRandom = new SplittableRandom(seed);
        long expectedFirst = new SplittableRandom(seed).nextLong();
        DestroyOperator destroy = new RandomRemoval();
        RepairOperator repair = new GreedyRepair();
        OperatorCompatibilityMatrix matrix = new OperatorCompatibilityMatrix(
                Map.of(destroy.id(), Set.of(repair.id())));

        SaCalibrationResult result = new SaCalibrator(fixture.full()).calibrate(
                fixture.problem(),
                fixture.initial(),
                List.of(destroy),
                List.of(repair),
                matrix,
                new AlnsIterationConfig(1.0d, 1, 1, 1, 2),
                4,
                seed,
                CONFIG.targetInitialAcceptanceProbability(),
                () -> false);

        assertEquals(SaCalibrator.deriveSeed(seed), result.derivedSeed());
        assertArrayEquals(assignments, fixture.state().assignments());
        assertEquals(fingerprint, fixture.state().fingerprint());
        assertEquals(expectedFirst, untouchedSearchRandom.nextLong());
        assertTrue(result.hardScale() > 0.0d);
        for (double scale : result.softScales()) {
            assertTrue(Double.isFinite(scale) && scale > 0.0d);
        }
    }

    private static LexicographicSaAcceptance acceptance(
            SaCalibrationResult calibration,
            java.util.function.DoubleSupplier random,
            boolean feasibleLocked) {
        return new LexicographicSaAcceptance(CONFIG, calibration, 100L, random, feasibleLocked);
    }

    private static SaCalibrationResult fallback() {
        return SaCalibrationResult.fallback(1L, CONFIG.targetInitialAcceptanceProbability());
    }

    private static SaCalibrationResult calibration(double hardScale, double[] softScales) {
        return new SaCalibrationResult(
                1L,
                1,
                1,
                hardScale,
                softScales,
                1,
                new int[] { 1, 1, 1, 1 },
                new double[] { 0.2d, 0.2d, 0.2d, 0.2d, 0.2d });
    }

    private static RosterScore score(int hard, int... soft) {
        return RosterScore.of(hard, soft);
    }
}
