package org.acme.solver.lahc;

import java.util.List;
import java.util.Objects;

import org.acme.solver.core.RosterScore;
import org.acme.solver.core.SolveMetrics;
import org.acme.solver.core.TerminationReason;

/**
 * 테스트 전용 ordered VND의 후보 생성·검증·수락 관측값입니다.
 *
 * <p>후보 평가는 모두 transaction 안에서 전체 점수로 검증한다. 따라서 이 메트릭의
 * {@code fullVerificationCount / evaluatedCandidates}는 탐색 후보에 대한 full-score
 * 검증 비율을 나타낸다.</p>
 */
public record OrderedVndLocalSearchMetrics(
        long generatedReassignCandidates,
        long generatedSwapCandidates,
        long evaluatedReassignCandidates,
        long evaluatedSwapCandidates,
        long acceptedReassignCandidates,
        long acceptedSwapCandidates,
        long rejectedCandidates,
        long fullVerificationCount,
        long scoreMismatchFailures,
        long stateCorruptionFailures,
        int candidateLimitPerNeighborhood,
        List<BestImprovement> bestImprovements,
        TerminationReason terminationReason) implements SolveMetrics {

    public OrderedVndLocalSearchMetrics {
        if (generatedReassignCandidates < 0L || generatedSwapCandidates < 0L
                || evaluatedReassignCandidates < 0L || evaluatedSwapCandidates < 0L
                || acceptedReassignCandidates < 0L || acceptedSwapCandidates < 0L
                || rejectedCandidates < 0L || fullVerificationCount < 0L
                || scoreMismatchFailures < 0L || stateCorruptionFailures < 0L
                || candidateLimitPerNeighborhood < 1) {
            throw new IllegalArgumentException("ordered VND metric count가 올바르지 않습니다.");
        }
        if (acceptedReassignCandidates + acceptedSwapCandidates + rejectedCandidates
                != evaluatedReassignCandidates + evaluatedSwapCandidates) {
            throw new IllegalArgumentException("ordered VND 수락/거절 집계가 후보 평가 수와 다릅니다.");
        }
        Objects.requireNonNull(bestImprovements, "bestImprovements");
        Objects.requireNonNull(terminationReason, "terminationReason");
        bestImprovements = List.copyOf(bestImprovements);
    }

    @Override
    public String engineId() {
        return "POJO_ORDERED_CHANGE_SWAP_VND_TEST_ONLY";
    }

    public long evaluationCount() {
        return evaluatedReassignCandidates + evaluatedSwapCandidates;
    }

    public long rollbackFailureCount() {
        return stateCorruptionFailures;
    }

    public long rollbackAttemptCount() {
        return rejectedCandidates;
    }

    public record BestImprovement(long evaluation, long elapsedMillis, RosterScore score, String neighborhood) {
        public BestImprovement {
            if (evaluation < 0L || elapsedMillis < 0L) {
                throw new IllegalArgumentException("best improvement 시점은 음수일 수 없습니다.");
            }
            Objects.requireNonNull(score, "score");
            Objects.requireNonNull(neighborhood, "neighborhood");
        }
    }
}
