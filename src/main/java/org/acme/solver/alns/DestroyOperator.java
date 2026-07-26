package org.acme.solver.alns;

/** ALNS complete current에서 제거할 mutable shift 집합을 선택하는 SPI입니다. */
public interface DestroyOperator {

    String id();

    DestroyPlan destroy(DestroyContext context);
}
