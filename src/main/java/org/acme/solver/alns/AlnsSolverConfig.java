package org.acme.solver.alns;

import java.time.Duration;
import java.util.Objects;

/** ALNS iteration, SA, adaptive 학습과 분리 예산 설정입니다. */
public record AlnsSolverConfig(
        AlnsIterationConfig iterationConfig,
        SaAcceptanceConfig saConfig,
        AdaptiveOperatorConfig adaptiveConfig,
        long initialFeasibilityEvaluationBudget,
        int calibrationAttemptBudget,
        Duration finalValidationReserve) {

    public AlnsSolverConfig {
        Objects.requireNonNull(iterationConfig, "iterationConfig");
        Objects.requireNonNull(saConfig, "saConfig");
        Objects.requireNonNull(adaptiveConfig, "adaptiveConfig");
        Objects.requireNonNull(finalValidationReserve, "finalValidationReserve");
        if (initialFeasibilityEvaluationBudget < 0L) {
            throw new IllegalArgumentException("initialFeasibilityEvaluationBudget은 음수일 수 없습니다.");
        }
        if (calibrationAttemptBudget < 0) {
            throw new IllegalArgumentException("calibrationAttemptBudget은 음수일 수 없습니다.");
        }
        if (finalValidationReserve.isNegative()) {
            throw new IllegalArgumentException("finalValidationReserve는 음수일 수 없습니다.");
        }
    }
}
