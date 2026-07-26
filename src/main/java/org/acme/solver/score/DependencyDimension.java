package org.acme.solver.score;

/**
 * move가 제약 기여도에 영향을 줄 수 있는 축입니다.
 */
public enum DependencyDimension {
    SHIFT,
    EMPLOYEE,
    ACTUAL_DATE,
    LOGICAL_DATE,
    ADJACENT_REST_WINDOW,
    ACTUAL_MONTH,
    PRECEPTOR_RELATION_GROUP,
    FAIRNESS_AGGREGATE,
    IMMUTABLE_SHIFT_INDEX
}
