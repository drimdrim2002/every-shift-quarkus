package org.acme.solver.score;

/**
 * 전체 complete assignment를 읽기 전용으로 평가하는 제약 단위 계약입니다.
 */
public interface ConstraintEvaluator {

    String evaluatorId();

    ConstraintDependencyMetadata dependencyMetadata();

    void evaluate(ScoreEvaluationContext context, ContributionCollector collector);
}
