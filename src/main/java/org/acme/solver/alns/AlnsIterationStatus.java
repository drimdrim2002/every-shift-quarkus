package org.acme.solver.alns;

public enum AlnsIterationStatus {
    ACCEPTED,
    REJECTED,
    NO_MUTABLE_SHIFT,
    DESTROY_FAILED,
    REPAIR_FAILED,
    OPERATOR_EXCEPTION,
    CANCELLED
}
