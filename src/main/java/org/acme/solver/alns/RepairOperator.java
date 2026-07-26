package org.acme.solver.alns;

/** transaction 내부 partial assignment를 제한된 시도 안에 완성하는 SPI입니다. */
public interface RepairOperator {

    String id();

    RepairResult repair(RepairContext context);
}
