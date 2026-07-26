package org.acme.solver.score;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * 제약별 증분 재계산 범위 계약입니다.
 *
 * @param evaluatorId evaluator의 안정 ID
 * @param granularity 증분 캐시 분할 단위
 * @param dimensions 점수가 의존하는 영향 축
 * @param restBefore 변경 shift 시작 이전의 휴식 영향 범위
 * @param restAfter 변경 shift 종료 이후의 휴식 영향 범위
 * @param logicalDaysBefore 변경 논리일 이전 영향 일수
 * @param logicalDaysAfter 변경 논리일 이후 영향 일수
 */
public record ConstraintDependencyMetadata(
        String evaluatorId,
        EvaluationGranularity granularity,
        Set<DependencyDimension> dimensions,
        Duration restBefore,
        Duration restAfter,
        int logicalDaysBefore,
        int logicalDaysAfter) {

    public ConstraintDependencyMetadata {
        if (evaluatorId == null || evaluatorId.isBlank()) {
            throw new IllegalArgumentException("evaluatorId는 비어 있을 수 없습니다.");
        }
        Objects.requireNonNull(granularity, "granularity");
        dimensions = Set.copyOf(Objects.requireNonNull(dimensions, "dimensions"));
        restBefore = Objects.requireNonNull(restBefore, "restBefore");
        restAfter = Objects.requireNonNull(restAfter, "restAfter");
        if (restBefore.isNegative() || restAfter.isNegative()) {
            throw new IllegalArgumentException("휴식 영향 범위는 음수일 수 없습니다.");
        }
        if (logicalDaysBefore < 0 || logicalDaysAfter < 0) {
            throw new IllegalArgumentException("논리일 영향 범위는 음수일 수 없습니다.");
        }
        if (granularity == EvaluationGranularity.SHIFT
                && !dimensions.contains(DependencyDimension.SHIFT)) {
            throw new IllegalArgumentException("SHIFT granularity에는 SHIFT dependency가 필요합니다.");
        }
        if (granularity == EvaluationGranularity.EMPLOYEE
                && !dimensions.contains(DependencyDimension.EMPLOYEE)) {
            throw new IllegalArgumentException("EMPLOYEE granularity에는 EMPLOYEE dependency가 필요합니다.");
        }
    }

    public static ConstraintDependencyMetadata shift(String evaluatorId, DependencyDimension... extra) {
        java.util.LinkedHashSet<DependencyDimension> dimensions = new java.util.LinkedHashSet<>();
        dimensions.add(DependencyDimension.SHIFT);
        dimensions.add(DependencyDimension.EMPLOYEE);
        dimensions.add(DependencyDimension.IMMUTABLE_SHIFT_INDEX);
        dimensions.addAll(java.util.List.of(extra));
        return new ConstraintDependencyMetadata(
                evaluatorId, EvaluationGranularity.SHIFT, dimensions,
                Duration.ZERO, Duration.ZERO, 0, 0);
    }

    public static ConstraintDependencyMetadata employee(
            String evaluatorId,
            Duration restBefore,
            Duration restAfter,
            int logicalDaysBefore,
            int logicalDaysAfter,
            DependencyDimension... extra) {
        java.util.LinkedHashSet<DependencyDimension> dimensions = new java.util.LinkedHashSet<>();
        dimensions.add(DependencyDimension.EMPLOYEE);
        dimensions.add(DependencyDimension.IMMUTABLE_SHIFT_INDEX);
        dimensions.addAll(java.util.List.of(extra));
        return new ConstraintDependencyMetadata(
                evaluatorId, EvaluationGranularity.EMPLOYEE, dimensions,
                restBefore, restAfter, logicalDaysBefore, logicalDaysAfter);
    }
}
