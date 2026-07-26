package org.acme.solver.alns;

import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.random.RandomGenerator;

import org.acme.solver.core.RosterScore;

/**
 * hard와 첫 번째 다른 soft level만 사용하는 사전식 SA 수락 정책입니다.
 * 정규화 energy는 악화 후보 수락 확률에만 쓰고 best 비교에는 관여하지 않습니다.
 */
public final class LexicographicSaAcceptance implements AcceptancePolicy {

    public static final String ID = "LEXICOGRAPHIC_SA_V1";

    public enum DecisionType {
        HARD_IMPROVEMENT,
        HARD_WORSENING_SA,
        FEASIBLE_REGION_LOCK,
        SOFT_IMPROVEMENT,
        SOFT_WORSENING_SA,
        EQUAL
    }

    public record Decision(
            boolean accepted,
            DecisionType type,
            int softLevel,
            long loss,
            double energy,
            double temperature,
            double probability) {
    }

    private final SaAcceptanceConfig config;
    private final SaCalibrationResult calibration;
    private final long coolingEvaluations;
    private final DoubleSupplier randomUnit;
    private boolean feasibleRegionLocked;
    private long evaluationCount;
    private Decision lastDecision;

    public LexicographicSaAcceptance(
            SaAcceptanceConfig config,
            SaCalibrationResult calibration,
            long coolingEvaluations,
            RandomGenerator random,
            boolean feasibleRegionLocked) {
        this(config, calibration, coolingEvaluations,
                (DoubleSupplier) Objects.requireNonNull(random, "random")::nextDouble,
                feasibleRegionLocked);
    }

    public LexicographicSaAcceptance(
            SaAcceptanceConfig config,
            SaCalibrationResult calibration,
            long coolingEvaluations,
            DoubleSupplier randomUnit,
            boolean feasibleRegionLocked) {
        this.config = Objects.requireNonNull(config, "config");
        this.calibration = Objects.requireNonNull(calibration, "calibration");
        if (coolingEvaluations <= 0L) {
            throw new IllegalArgumentException("coolingEvaluations는 양수여야 합니다.");
        }
        this.coolingEvaluations = coolingEvaluations;
        this.randomUnit = Objects.requireNonNull(randomUnit, "randomUnit");
        this.feasibleRegionLocked = feasibleRegionLocked;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean accept(RosterScore currentScore, RosterScore candidateScore) {
        return decide(currentScore, candidateScore).accepted();
    }

    public Decision decide(RosterScore currentScore, RosterScore candidateScore) {
        Objects.requireNonNull(currentScore, "currentScore");
        Objects.requireNonNull(candidateScore, "candidateScore");

        double temperature = temperatureAt(evaluationCount);
        Decision decision;
        if (feasibleRegionLocked && !candidateScore.isFeasible()) {
            decision = deterministic(false, DecisionType.FEASIBLE_REGION_LOCK, -1, temperature);
        } else {
            long hardDelta = candidateScore.hardDeltaFrom(currentScore);
            if (hardDelta > 0L) {
                decision = deterministic(true, DecisionType.HARD_IMPROVEMENT, -1, temperature);
            } else if (hardDelta < 0L) {
                long loss = -hardDelta;
                decision = probabilistic(
                        DecisionType.HARD_WORSENING_SA,
                        -1,
                        loss,
                        calibration.hardScale(),
                        temperature);
            } else {
                decision = compareSoft(currentScore, candidateScore, temperature);
            }
        }

        evaluationCount++;
        if (decision.accepted() && candidateScore.isFeasible()) {
            feasibleRegionLocked = true;
        }
        lastDecision = decision;
        return decision;
    }

    public long evaluationCount() {
        return evaluationCount;
    }

    public boolean feasibleRegionLocked() {
        return feasibleRegionLocked;
    }

    public Decision lastDecision() {
        return lastDecision;
    }

    public double temperatureAt(long completedEvaluations) {
        if (completedEvaluations < 0L) {
            throw new IllegalArgumentException("completedEvaluations는 음수일 수 없습니다.");
        }
        double progress;
        if (coolingEvaluations == 1L) {
            progress = 0.0d;
        } else {
            progress = Math.min(1.0d,
                    (double) completedEvaluations / (double) (coolingEvaluations - 1L));
        }
        double temperature = config.initialTemperature()
                * Math.pow(config.finalTemperatureRatio(), progress);
        if (!Double.isFinite(temperature) || temperature <= 0.0d) {
            throw new IllegalStateException("SA temperature가 유한한 양수가 아닙니다: " + temperature);
        }
        return temperature;
    }

    private Decision compareSoft(
            RosterScore currentScore,
            RosterScore candidateScore,
            double temperature) {
        for (int level = 0; level < RosterScore.SOFT_LEVELS; level++) {
            long delta = candidateScore.softDeltaFrom(currentScore, level);
            if (delta > 0L) {
                return deterministic(true, DecisionType.SOFT_IMPROVEMENT, level, temperature);
            }
            if (delta < 0L) {
                return probabilistic(
                        DecisionType.SOFT_WORSENING_SA,
                        level,
                        -delta,
                        calibration.softScale(level),
                        temperature);
            }
        }
        return deterministic(true, DecisionType.EQUAL, -1, temperature);
    }

    private Decision probabilistic(
            DecisionType type,
            int softLevel,
            long loss,
            double scale,
            double temperature) {
        double energy = (double) loss / scale;
        if (!Double.isFinite(energy) || energy <= 0.0d) {
            throw new IllegalStateException("SA energy가 유한한 양수가 아닙니다: " + energy);
        }
        double probability = Math.exp(-energy / temperature);
        if (!Double.isFinite(probability) || probability < 0.0d || probability > 1.0d) {
            throw new IllegalStateException("SA 수락 확률이 0..1 범위를 벗어났습니다: " + probability);
        }
        double draw = randomUnit.getAsDouble();
        if (!Double.isFinite(draw) || draw < 0.0d || draw >= 1.0d) {
            throw new IllegalStateException("SA RNG는 유한한 [0, 1) 값을 반환해야 합니다: " + draw);
        }
        return new Decision(draw < probability, type, softLevel, loss, energy, temperature, probability);
    }

    private static Decision deterministic(
            boolean accepted,
            DecisionType type,
            int softLevel,
            double temperature) {
        return new Decision(accepted, type, softLevel, 0L, 0.0d, temperature,
                accepted ? 1.0d : 0.0d);
    }
}
