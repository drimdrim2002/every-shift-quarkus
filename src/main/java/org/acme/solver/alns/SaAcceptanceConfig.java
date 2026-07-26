package org.acme.solver.alns;

/** 사전식 SA의 온도 정책입니다. */
public record SaAcceptanceConfig(
        double targetInitialAcceptanceProbability,
        double finalTemperatureRatio,
        long fallbackCoolingEvaluations) {

    public SaAcceptanceConfig {
        if (!Double.isFinite(targetInitialAcceptanceProbability)
                || targetInitialAcceptanceProbability <= 0.0d
                || targetInitialAcceptanceProbability >= 1.0d) {
            throw new IllegalArgumentException("targetInitialAcceptanceProbability는 0과 1 사이여야 합니다.");
        }
        if (!Double.isFinite(finalTemperatureRatio)
                || finalTemperatureRatio <= 0.0d
                || finalTemperatureRatio > 1.0d) {
            throw new IllegalArgumentException("finalTemperatureRatio는 0 초과 1 이하여야 합니다.");
        }
        if (fallbackCoolingEvaluations <= 0L) {
            throw new IllegalArgumentException("fallbackCoolingEvaluations는 양수여야 합니다.");
        }
    }

    public double initialTemperature() {
        return -1.0d / Math.log(targetInitialAcceptanceProbability);
    }
}
