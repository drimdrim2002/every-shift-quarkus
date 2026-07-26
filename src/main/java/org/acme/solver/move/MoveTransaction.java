package org.acme.solver.move;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.score.IncrementalScoreCalculator;
import org.acme.solver.score.ScoreCacheFingerprint;
import org.acme.solver.score.ScoreMismatchException;

/**
 * 여러 move를 하나의 원자적 후보로 묶습니다.
 * reject·예외·취소는 적용 역순으로 rollback하고 baseline fingerprint/score/cache를 검증합니다.
 */
public final class MoveTransaction implements AutoCloseable {

    private final SearchState state;
    private final IncrementalScoreCalculator calculator;
    private final MoveImpactResolver impactResolver;
    private final List<AppliedMove> appliedMoves = new ArrayList<>();
    private MoveTransactionStatus status = MoveTransactionStatus.NEW;
    private SolutionFingerprint baselineFingerprint;
    private RosterScore baselineScore;
    private ScoreCacheFingerprint baselineCacheFingerprint;
    private RosterSolution verifiedCandidate;

    public MoveTransaction(SearchState state, IncrementalScoreCalculator calculator) {
        this.state = Objects.requireNonNull(state, "state");
        this.calculator = Objects.requireNonNull(calculator, "calculator");
        if (state.problem() != calculator.problem()) {
            throw new IllegalArgumentException("SearchState와 calculator는 같은 PlanningProblem을 사용해야 합니다.");
        }
        this.impactResolver = new MoveImpactResolver(
                state.problem(), calculator.dependencyMetadata());
    }

    public static MoveTransaction open(
            SearchState state, IncrementalScoreCalculator calculator) {
        return new MoveTransaction(state, calculator).begin();
    }

    public MoveTransaction begin() {
        requireStatus(MoveTransactionStatus.NEW);
        if (!state.internalScore().equals(calculator.currentScore())) {
            throw new IllegalStateException("SearchState와 incremental score가 동기화되지 않았습니다.");
        }
        if (!state.immutableAssignmentsIntact()) {
            throw corruption("transaction 시작 전 immutable assignment가 손상되었습니다.", null);
        }
        SolutionFingerprint recomputed = SolutionFingerprint.from(state.internalSnapshot());
        if (!recomputed.equals(state.internalFingerprint())) {
            throw corruption("transaction 시작 전 solution fingerprint가 손상되었습니다.", null);
        }
        baselineFingerprint = state.internalFingerprint();
        baselineScore = state.internalScore();
        baselineCacheFingerprint = calculator.cacheFingerprint();
        state.beginTransaction();
        status = MoveTransactionStatus.ACTIVE;
        return this;
    }

    public MoveTransactionStatus status() {
        return status;
    }

    public int appliedMoveCount() {
        return appliedMoves.size();
    }

    public MoveImpact apply(Move move) {
        requireStatus(MoveTransactionStatus.ACTIVE);
        Objects.requireNonNull(move, "move");
        MoveImpact impact = null;
        SolutionFingerprint beforeMove = state.internalFingerprint();
        try {
            List<AssignmentChange> validated = MoveSupport.validateChanges(
                    state.problem(), move.changes());
            MoveSupport.validateExpected(state, validated, false);
            impact = impactResolver.resolve(move);
            move.apply(state);
            MoveSupport.validateExpected(state, validated, true);
            if (!state.immutableAssignmentsIntact()) {
                throw new IllegalStateException("move가 immutable assignment를 변경했습니다.");
            }
            RosterScore nextScore = calculator.refreshAfterMove(state.internalSnapshot(), impact);
            state.internalScore(nextScore);
            appliedMoves.add(new AppliedMove(move, impact));
            verifiedCandidate = null;
            return impact;
        } catch (Throwable failure) {
            Throwable recoveryFailure = null;
            if (!state.internalFingerprint().equals(beforeMove)) {
                try {
                    move.undo(state);
                } catch (Throwable undoFailure) {
                    recoveryFailure = undoFailure;
                }
            }
            try {
                rollbackInternal();
            } catch (StateCorruptionException corruption) {
                if (recoveryFailure != null) {
                    corruption.addSuppressed(recoveryFailure);
                }
                corruption.addSuppressed(failure);
                throw corruption;
            }
            if (recoveryFailure != null) {
                StateCorruptionException corruption = corruption(
                        "실패한 move의 역연산이 상태를 복구하지 못했습니다.", recoveryFailure);
                corruption.addSuppressed(failure);
                throw corruption;
            }
            throw propagate(failure);
        }
    }

    /** candidate complete 상태에서 full/incremental 일치를 확인합니다. */
    public RosterSolution verifyCandidate() {
        requireStatus(MoveTransactionStatus.ACTIVE);
        try {
            RosterSolution verified = calculator.verifyAgainstFull(state.internalSnapshot());
            state.internalScore(verified.score());
            verifiedCandidate = verified;
            return verified;
        } catch (ScoreMismatchException mismatch) {
            try {
                rollbackInternal();
            } catch (StateCorruptionException corruption) {
                corruption.addSuppressed(mismatch);
                throw corruption;
            }
            throw mismatch;
        }
    }

    /** 활성 transaction의 내부 candidate score이며 API/best snapshot을 만들지 않습니다. */
    public RosterScore candidateScore() {
        requireStatus(MoveTransactionStatus.ACTIVE);
        return state.internalScore();
    }

    public void commit() {
        requireStatus(MoveTransactionStatus.ACTIVE);
        if (!state.immutableAssignmentsIntact()) {
            throw corruption("commit 직전 immutable assignment가 손상되었습니다.", null);
        }
        if (!state.internalScore().equals(calculator.currentScore())) {
            throw corruption("commit 직전 score cache가 SearchState와 다릅니다.", null);
        }
        SolutionFingerprint recomputed = SolutionFingerprint.from(state.internalSnapshot());
        if (!recomputed.equals(state.internalFingerprint())) {
            throw corruption("commit 직전 solution fingerprint가 다릅니다.", null);
        }
        if (verifiedCandidate != null) {
            try {
                RosterSolution remembered = calculator.verifyAndRememberBest(state.internalSnapshot());
                state.internalScore(remembered.score());
            } catch (ScoreMismatchException mismatch) {
                try {
                    rollbackInternal();
                } catch (StateCorruptionException corruption) {
                    corruption.addSuppressed(mismatch);
                    throw corruption;
                }
                throw mismatch;
            }
        }
        state.endTransaction();
        status = MoveTransactionStatus.COMMITTED;
    }

    /** reject 경로입니다. */
    public void rollback() {
        requireStatus(MoveTransactionStatus.ACTIVE);
        rollbackInternal();
    }

    /** cancel 경로입니다. */
    public void cancel() {
        rollback();
    }

    public void throwIfCancelled(BooleanSupplier cancellationToken) {
        requireStatus(MoveTransactionStatus.ACTIVE);
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        if (cancellationToken.getAsBoolean()) {
            rollbackInternal();
            throw new MoveCancelledException();
        }
    }

    @Override
    public void close() {
        if (status == MoveTransactionStatus.ACTIVE) {
            rollbackInternal();
        }
    }

    private void rollbackInternal() {
        if (status != MoveTransactionStatus.ACTIVE) {
            if (status == MoveTransactionStatus.ROLLING_BACK) {
                throw corruption("재진입 rollback이 감지되었습니다.", null);
            }
            return;
        }
        status = MoveTransactionStatus.ROLLING_BACK;
        Throwable rollbackFailure = null;
        for (int index = appliedMoves.size() - 1; index >= 0; index--) {
            AppliedMove applied = appliedMoves.get(index);
            try {
                applied.move().undo(state);
                RosterScore restored = calculator.refreshAfterMove(
                        state.internalSnapshot(), applied.impact());
                state.internalScore(restored);
            } catch (Throwable failure) {
                rollbackFailure = failure;
                break;
            }
        }

        boolean restored = rollbackFailure == null
                && baselineFingerprint.equals(state.internalFingerprint())
                && baselineScore.equals(state.internalScore())
                && baselineCacheFingerprint.equals(calculator.cacheFingerprint())
                && state.immutableAssignmentsIntact()
                && baselineFingerprint.equals(SolutionFingerprint.from(state.internalSnapshot()));
        if (!restored) {
            throw corruption("rollback 뒤 fingerprint/score/cache/immutable index 복구에 실패했습니다.", rollbackFailure);
        }
        state.endTransaction();
        status = MoveTransactionStatus.ROLLED_BACK;
    }

    private StateCorruptionException corruption(String message, Throwable cause) {
        status = MoveTransactionStatus.CORRUPTED;
        state.markCorrupted();
        return new StateCorruptionException(message, cause, calculator.lastVerifiedBest());
    }

    private void requireStatus(MoveTransactionStatus required) {
        if (status != required) {
            throw new IllegalStateException(
                    "MoveTransaction 상태가 올바르지 않습니다: required=" + required + ", actual=" + status);
        }
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("move 적용 중 예외가 발생했습니다.", failure);
    }

    private record AppliedMove(Move move, MoveImpact impact) {
    }
}
