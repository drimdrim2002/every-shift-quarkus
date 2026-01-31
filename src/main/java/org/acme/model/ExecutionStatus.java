package org.acme.model;

/**
 * Job Execution 상태 Enum
 *
 * - PENDING: 큐에 대기 중
 * - RUNNING: solver 실행 중
 * - COMPLETED: 완료
 * - FAILED: 실패
 */
public enum ExecutionStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}
