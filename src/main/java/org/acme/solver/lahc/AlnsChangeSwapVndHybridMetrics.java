package org.acme.solver.lahc;

import java.util.List;
import java.util.Objects;

import org.acme.solver.core.RosterScore;
import org.acme.solver.core.SolveMetrics;
import org.acme.solver.core.TerminationReason;

/** ALNS 뒤 ordered VND를 연결하는 test-only 후보의 단계별 계약입니다. */
public record AlnsChangeSwapVndHybridMetrics(
        String mode,
        long rootSeed,
        long totalEvaluationCount,
        List<Stage> stages,
        long scoreMismatchFailures,
        long stateCorruptionFailures,
        TerminationReason terminationReason) implements SolveMetrics {

    public AlnsChangeSwapVndHybridMetrics {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(stages, "stages");
        Objects.requireNonNull(terminationReason, "terminationReason");
        if (totalEvaluationCount < 0L || scoreMismatchFailures < 0L || stateCorruptionFailures < 0L) {
            throw new IllegalArgumentException("hybrid VND metric count가 올바르지 않습니다.");
        }
        stages = List.copyOf(stages);
    }

    @Override
    public String engineId() {
        return "POJO_ALNS_CHANGE_SWAP_VND_TEST_ONLY";
    }

    public long rollbackFailureCount() {
        return stateCorruptionFailures;
    }

    public record Stage(
            String stageId,
            long derivedSeed,
            long reservedEvaluationBudget,
            long availableEvaluationBudget,
            long evaluationCount,
            long elapsedMillis,
            long unusedEvaluationBudget,
            long reservedWallMillis,
            long availableWallMillis,
            long unusedWallMillis,
            RosterScore inputScore,
            RosterScore outputScore,
            TerminationReason terminationReason,
            SolveMetrics stageMetrics) {
        public Stage {
            Objects.requireNonNull(stageId, "stageId");
            Objects.requireNonNull(inputScore, "inputScore");
            Objects.requireNonNull(outputScore, "outputScore");
            Objects.requireNonNull(terminationReason, "terminationReason");
            Objects.requireNonNull(stageMetrics, "stageMetrics");
            if (reservedEvaluationBudget < -1L || availableEvaluationBudget < -1L
                    || evaluationCount < 0L || elapsedMillis < 0L || unusedEvaluationBudget < -1L
                    || reservedWallMillis < -1L || availableWallMillis < -1L || unusedWallMillis < -1L) {
                throw new IllegalArgumentException("hybrid VND stage metric이 올바르지 않습니다.");
            }
        }
    }
}
