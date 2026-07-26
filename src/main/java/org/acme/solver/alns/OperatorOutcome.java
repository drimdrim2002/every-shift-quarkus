package org.acme.solver.alns;

/** complete 후보 평가 뒤 한 operator pair에 기록하는 결과입니다. */
public enum OperatorOutcome {
    GLOBAL_BEST,
    CURRENT_IMPROVEMENT,
    ACCEPTED_WORSENING,
    REJECTED
}
