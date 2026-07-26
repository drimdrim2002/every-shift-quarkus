package org.acme.solver.alns;

/** 안정 index별 operator 선택·성과·현재 weight snapshot입니다. */
public record OperatorStatistics(
        int index,
        String operatorId,
        double weight,
        long selectionCount,
        long globalBestCount,
        long currentImprovementCount,
        long acceptedWorseningCount,
        long rejectionCount) {
}
