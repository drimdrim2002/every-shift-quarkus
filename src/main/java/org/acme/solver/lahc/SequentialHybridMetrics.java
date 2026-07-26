package org.acme.solver.lahc;

import java.util.List;
import java.util.Objects;

import org.acme.solver.core.RosterScore;
import org.acme.solver.core.SolveMetrics;
import org.acme.solver.core.TerminationReason;

/** 테스트 전용 순차 hybrid의 단계별 예산·결과 경계입니다. */
public record SequentialHybridMetrics(
        String order,
        long rootSeed,
        long totalEvaluationCount,
        List<Stage> stages,
        long scoreMismatchFailures,
        long stateCorruptionFailures,
        TerminationReason terminationReason) implements SolveMetrics {

    public SequentialHybridMetrics {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(stages, "stages");
        Objects.requireNonNull(terminationReason, "terminationReason");
        if (totalEvaluationCount < 0L || scoreMismatchFailures < 0L || stateCorruptionFailures < 0L) {
            throw new IllegalArgumentException("hybrid metric count가 올바르지 않습니다.");
        }
        stages = List.copyOf(stages);
    }

    @Override
    public String engineId() {
        return "POJO_SEQUENTIAL_HYBRID";
    }

    public long evaluationCount() {
        return stages.stream().mapToLong(Stage::evaluationCount).sum();
    }

    public long rollbackFailureCount() {
        return stateCorruptionFailures;
    }

    public record Stage(
            String engineId,
            long derivedSeed,
            long evaluationBudget,
            long evaluationCount,
            long elapsedMillis,
            RosterScore inputScore,
            RosterScore outputScore,
            TerminationReason terminationReason) {

        public Stage {
            Objects.requireNonNull(engineId, "engineId");
            Objects.requireNonNull(inputScore, "inputScore");
            Objects.requireNonNull(outputScore, "outputScore");
            Objects.requireNonNull(terminationReason, "terminationReason");
            if (evaluationBudget < -1L || evaluationCount < 0L || elapsedMillis < 0L) {
                throw new IllegalArgumentException("hybrid stage metric이 올바르지 않습니다.");
            }
        }
    }
}
