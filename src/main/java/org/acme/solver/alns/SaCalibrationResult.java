package org.acme.solver.alns;

import java.util.Arrays;

import org.acme.solver.core.RosterScore;

/** 탐색 state/RNG와 분리된 SA scale 보정 결과입니다. */
public final class SaCalibrationResult {

    private final long derivedSeed;
    private final int attempts;
    private final int evaluatedCandidates;
    private final double hardScale;
    private final double[] softScales;
    private final int hardSampleCount;
    private final int[] softSampleCounts;
    private final double[] estimatedInitialAcceptanceRates;

    public SaCalibrationResult(
            long derivedSeed,
            int attempts,
            int evaluatedCandidates,
            double hardScale,
            double[] softScales,
            int hardSampleCount,
            int[] softSampleCounts,
            double[] estimatedInitialAcceptanceRates) {
        if (attempts < 0 || evaluatedCandidates < 0 || evaluatedCandidates > attempts) {
            throw new IllegalArgumentException("calibration 시도/평가 횟수가 올바르지 않습니다.");
        }
        if (hardSampleCount < 0) {
            throw new IllegalArgumentException("hardSampleCount는 음수일 수 없습니다.");
        }
        requirePositiveFinite(hardScale, "hardScale");
        if (softScales == null || softScales.length != RosterScore.SOFT_LEVELS) {
            throw new IllegalArgumentException("softScales는 4개여야 합니다.");
        }
        if (softSampleCounts == null || softSampleCounts.length != RosterScore.SOFT_LEVELS) {
            throw new IllegalArgumentException("softSampleCounts는 4개여야 합니다.");
        }
        if (estimatedInitialAcceptanceRates == null
                || estimatedInitialAcceptanceRates.length != RosterScore.SOFT_LEVELS + 1) {
            throw new IllegalArgumentException("초기 수락률은 hard 1개와 soft 4개여야 합니다.");
        }
        for (int index = 0; index < RosterScore.SOFT_LEVELS; index++) {
            requirePositiveFinite(softScales[index], "softScales[" + index + "]");
            if (softSampleCounts[index] < 0) {
                throw new IllegalArgumentException("softSampleCounts는 음수일 수 없습니다.");
            }
        }
        for (double rate : estimatedInitialAcceptanceRates) {
            if (!Double.isFinite(rate) || rate < 0.0d || rate > 1.0d) {
                throw new IllegalArgumentException("초기 수락률은 유한한 0..1 값이어야 합니다.");
            }
        }
        this.derivedSeed = derivedSeed;
        this.attempts = attempts;
        this.evaluatedCandidates = evaluatedCandidates;
        this.hardScale = hardScale;
        this.softScales = Arrays.copyOf(softScales, softScales.length);
        this.hardSampleCount = hardSampleCount;
        this.softSampleCounts = Arrays.copyOf(softSampleCounts, softSampleCounts.length);
        this.estimatedInitialAcceptanceRates = Arrays.copyOf(
                estimatedInitialAcceptanceRates, estimatedInitialAcceptanceRates.length);
    }

    public static SaCalibrationResult fallback(long derivedSeed, double p0) {
        return new SaCalibrationResult(
                derivedSeed,
                0,
                0,
                1.0d,
                new double[] { 1.0d, 1.0d, 1.0d, 1.0d },
                0,
                new int[] { 0, 0, 0, 0 },
                new double[] { p0, p0, p0, p0, p0 });
    }

    public long derivedSeed() {
        return derivedSeed;
    }

    public int attempts() {
        return attempts;
    }

    public int evaluatedCandidates() {
        return evaluatedCandidates;
    }

    public double hardScale() {
        return hardScale;
    }

    public double softScale(int level) {
        return softScales[level];
    }

    public double[] softScales() {
        return Arrays.copyOf(softScales, softScales.length);
    }

    public int hardSampleCount() {
        return hardSampleCount;
    }

    public int softSampleCount(int level) {
        return softSampleCounts[level];
    }

    public int[] softSampleCounts() {
        return Arrays.copyOf(softSampleCounts, softSampleCounts.length);
    }

    public double estimatedInitialAcceptanceRate(int scoreVectorIndex) {
        return estimatedInitialAcceptanceRates[scoreVectorIndex];
    }

    public double[] estimatedInitialAcceptanceRates() {
        return Arrays.copyOf(estimatedInitialAcceptanceRates, estimatedInitialAcceptanceRates.length);
    }

    private static void requirePositiveFinite(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0d) {
            throw new IllegalArgumentException(name + "은 유한한 양수여야 합니다: " + value);
        }
    }
}
