package org.acme.solver.move;

import java.util.Arrays;
import java.util.Objects;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;

/**
 * transaction 안에서만 변경되는 complete search state입니다.
 * transaction이 열린 동안에는 score, snapshot, fingerprint를 외부에 노출하지 않습니다.
 */
public final class SearchState {

    private static final int MUTABLE_SENTINEL = -2;

    private final PlanningProblem problem;
    private final int[] employeeIndexByShift;
    private final int[] immutableEmployeeIndexByShift;
    private RosterScore score;
    private SolutionFingerprint fingerprint;
    private boolean transactionActive;
    private boolean corrupted;

    public SearchState(PlanningProblem problem, RosterSolution initialSolution) {
        this.problem = Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(initialSolution, "initialSolution");
        if (initialSolution.employeeCount() != problem.employeeCount()
                || initialSolution.shiftCount() != problem.shiftCount()) {
            throw new IllegalArgumentException("initialSolution과 PlanningProblem의 크기가 다릅니다.");
        }
        this.employeeIndexByShift = initialSolution.employeeIndexByShift();
        this.immutableEmployeeIndexByShift = new int[problem.shiftCount()];
        for (int shiftIndex = 0; shiftIndex < problem.shiftCount(); shiftIndex++) {
            immutableEmployeeIndexByShift[shiftIndex] = problem.isMutableShift(shiftIndex)
                    ? MUTABLE_SENTINEL
                    : employeeIndexByShift[shiftIndex];
        }
        this.score = initialSolution.score();
        this.fingerprint = SolutionFingerprint.from(initialSolution);
    }

    public PlanningProblem problem() {
        return problem;
    }

    public int employeeIndex(int shiftIndex) {
        ensureUsable();
        return employeeIndexByShift[shiftIndex];
    }

    public RosterScore score() {
        ensureExternallyReadable();
        return score;
    }

    public SolutionFingerprint fingerprint() {
        ensureExternallyReadable();
        return fingerprint;
    }

    public int[] assignments() {
        ensureExternallyReadable();
        return Arrays.copyOf(employeeIndexByShift, employeeIndexByShift.length);
    }

    public RosterSolution snapshot() {
        ensureExternallyReadable();
        return new RosterSolution(problem.employeeCount(), employeeIndexByShift, score);
    }

    public boolean isTransactionActive() {
        return transactionActive;
    }

    public boolean isCorrupted() {
        return corrupted;
    }

    void beginTransaction() {
        ensureUsable();
        if (transactionActive) {
            throw new IllegalStateException("중첩 MoveTransaction은 허용되지 않습니다.");
        }
        transactionActive = true;
    }

    void endTransaction() {
        if (!transactionActive) {
            throw new IllegalStateException("활성 transaction이 없습니다.");
        }
        transactionActive = false;
    }

    void markCorrupted() {
        corrupted = true;
        transactionActive = false;
    }

    void changeAssignment(int shiftIndex, int expectedEmployeeIndex, int newEmployeeIndex) {
        ensureUsable();
        if (!transactionActive) {
            throw new IllegalStateException("assignment는 활성 MoveTransaction 안에서만 변경할 수 있습니다.");
        }
        if (!problem.isMutableShift(shiftIndex)) {
            throw new IllegalArgumentException("pinned/immutable shift는 변경할 수 없습니다: " + shiftIndex);
        }
        if (newEmployeeIndex < 0 || newEmployeeIndex >= problem.employeeCount()) {
            throw new IllegalArgumentException("newEmployeeIndex 범위를 벗어났습니다: " + newEmployeeIndex);
        }
        int actualEmployeeIndex = employeeIndexByShift[shiftIndex];
        if (actualEmployeeIndex != expectedEmployeeIndex) {
            throw new IllegalStateException(
                    "stale move입니다: shiftIndex=" + shiftIndex
                            + ", expected=" + expectedEmployeeIndex
                            + ", actual=" + actualEmployeeIndex);
        }
        employeeIndexByShift[shiftIndex] = newEmployeeIndex;
        fingerprint = fingerprint.replace(shiftIndex, expectedEmployeeIndex, newEmployeeIndex);
    }

    boolean immutableAssignmentsIntact() {
        for (int shiftIndex = 0; shiftIndex < immutableEmployeeIndexByShift.length; shiftIndex++) {
            int immutableEmployee = immutableEmployeeIndexByShift[shiftIndex];
            if (immutableEmployee != MUTABLE_SENTINEL
                    && employeeIndexByShift[shiftIndex] != immutableEmployee) {
                return false;
            }
        }
        return true;
    }

    RosterScore internalScore() {
        return score;
    }

    void internalScore(RosterScore score) {
        this.score = Objects.requireNonNull(score, "score");
    }

    SolutionFingerprint internalFingerprint() {
        return fingerprint;
    }

    RosterSolution internalSnapshot() {
        return new RosterSolution(problem.employeeCount(), employeeIndexByShift, score);
    }

    private void ensureExternallyReadable() {
        ensureUsable();
        if (transactionActive) {
            throw new IllegalStateException("활성 transaction의 중간 상태는 외부에 노출할 수 없습니다.");
        }
    }

    private void ensureUsable() {
        if (corrupted) {
            throw new IllegalStateException("STATE_CORRUPTION 상태는 더 이상 사용할 수 없습니다.");
        }
    }
}
