package org.acme.solver.score;

import java.util.List;
import java.util.Objects;

import org.acme.solver.core.PlanningProblem;

/**
 * 하나의 위반·보상 또는 직원 단위 집계가 최종 점수에 미친 영향입니다.
 */
public record ConstraintContribution(
        String constraintId,
        ScoreLevel level,
        int contribution,
        List<Integer> employeeIndexes,
        List<Integer> shiftIndexes) {

    public ConstraintContribution {
        Objects.requireNonNull(constraintId, "constraintId");
        Objects.requireNonNull(level, "level");
        employeeIndexes = List.copyOf(Objects.requireNonNull(employeeIndexes, "employeeIndexes"));
        shiftIndexes = List.copyOf(Objects.requireNonNull(shiftIndexes, "shiftIndexes"));
    }

    /**
     * 운영 개인정보인 이름 없이 외부 employee ID와 shift planning ID로 진단합니다.
     */
    public String describe(PlanningProblem problem) {
        Objects.requireNonNull(problem, "problem");
        List<String> employeeIds = employeeIndexes.stream()
                .map(index -> problem.employees().get(index).externalId())
                .toList();
        List<Long> shiftIds = shiftIndexes.stream()
                .map(index -> problem.shifts().get(index).planningId())
                .toList();
        return constraintId + " " + level + "=" + contribution
                + ", employees=" + employeeIds + ", shifts=" + shiftIds;
    }
}
