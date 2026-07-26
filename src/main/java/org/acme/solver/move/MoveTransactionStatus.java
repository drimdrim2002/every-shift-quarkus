package org.acme.solver.move;

/** MoveTransaction의 단방향 상태 전이입니다. */
public enum MoveTransactionStatus {
    NEW,
    ACTIVE,
    ROLLING_BACK,
    COMMITTED,
    ROLLED_BACK,
    CORRUPTED
}
