package org.acme.solver.initial;

import java.util.List;

import org.acme.solver.core.RosterSolution;

/** complete solution 또는 구조화된 실패 중 하나만 보유합니다. */
public record InitialSolutionResult(
        RosterSolution solution,
        List<InitialSolutionFailure> failures) {

    public InitialSolutionResult {
        failures = List.copyOf(failures == null ? List.of() : failures);
        if ((solution == null) == failures.isEmpty()) {
            throw new IllegalArgumentException("solution과 failures 중 정확히 하나가 존재해야 합니다.");
        }
    }

    public static InitialSolutionResult success(RosterSolution solution) {
        return new InitialSolutionResult(java.util.Objects.requireNonNull(solution, "solution"), List.of());
    }

    public static InitialSolutionResult failure(InitialSolutionFailure failure) {
        return new InitialSolutionResult(null, List.of(java.util.Objects.requireNonNull(failure, "failure")));
    }

    public boolean succeeded() {
        return solution != null;
    }

    public boolean feasible() {
        return solution != null && solution.score().isFeasible();
    }
}
