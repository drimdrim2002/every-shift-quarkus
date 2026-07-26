package org.acme.solver.core;

/**
 * 탐색 구현과 실행 계층 사이의 엔진 경계입니다.
 */
public interface SolverEngine {

    SolveResult<RosterSolution> solve(
            PlanningProblem problem,
            SolveOptions options,
            SolveListener listener);
}
