package org.acme.solver.move;

import org.acme.solver.core.TerminationReason;

/** transaction 취소가 rollback 완료 뒤 외부로 전달되는 신호입니다. */
public final class MoveCancelledException extends RuntimeException {

    public MoveCancelledException() {
        super("MoveTransaction이 취소되어 rollback되었습니다.");
    }

    public TerminationReason terminationReason() {
        return TerminationReason.CANCELLED;
    }
}
