package org.acme.solver.alns;

/** destroy/repair 독립 가중치 학습 설정입니다. */
public record AdaptiveOperatorConfig(
        double initialWeight,
        double minimumWeight,
        double reactionFactor,
        int segmentLength,
        double globalBestReward,
        double currentImprovementReward,
        double acceptedWorseningReward,
        double rejectionReward) {

    public AdaptiveOperatorConfig {
        requirePositiveFinite(initialWeight, "initialWeight");
        requirePositiveFinite(minimumWeight, "minimumWeight");
        if (!Double.isFinite(reactionFactor) || reactionFactor < 0.0d || reactionFactor > 1.0d) {
            throw new IllegalArgumentException("reactionFactor는 유한한 0..1 값이어야 합니다.");
        }
        if (segmentLength <= 0) {
            throw new IllegalArgumentException("segmentLength는 양수여야 합니다.");
        }
        requireNonNegativeFinite(globalBestReward, "globalBestReward");
        requireNonNegativeFinite(currentImprovementReward, "currentImprovementReward");
        requireNonNegativeFinite(acceptedWorseningReward, "acceptedWorseningReward");
        requireNonNegativeFinite(rejectionReward, "rejectionReward");
    }

    public double reward(OperatorOutcome outcome) {
        return switch (outcome) {
            case GLOBAL_BEST -> globalBestReward;
            case CURRENT_IMPROVEMENT -> currentImprovementReward;
            case ACCEPTED_WORSENING -> acceptedWorseningReward;
            case REJECTED -> rejectionReward;
        };
    }

    private static void requirePositiveFinite(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0d) {
            throw new IllegalArgumentException(name + "은 유한한 양수여야 합니다.");
        }
    }

    private static void requireNonNegativeFinite(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0d) {
            throw new IllegalArgumentException(name + "는 유한한 비음수여야 합니다.");
        }
    }
}
