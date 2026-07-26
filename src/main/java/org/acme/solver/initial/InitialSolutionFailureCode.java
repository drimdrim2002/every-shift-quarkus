package org.acme.solver.initial;

/** 초기해 생성 전에 탐색을 중단해야 하는 구조적 실패 분류입니다. */
public enum InitialSolutionFailureCode {
    NO_EMPLOYEE,
    UNASSIGNED_FIXED_SHIFT,
    FIXED_ASSIGNMENT_CONFLICT,
    MISSING_PRECEPTOR_REFERENCE,
    CYCLIC_PRECEPTOR_RELATION,
    NO_ASSIGNABLE_EMPLOYEE,
    NO_ATOMIC_RELATION_ASSIGNMENT,
    INTERRUPTED
}
