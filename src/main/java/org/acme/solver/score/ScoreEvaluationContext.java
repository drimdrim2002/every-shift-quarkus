package org.acme.solver.score;

import java.util.Objects;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterSolution;

/**
 * evaluator가 공유하는 불변 문제, complete assignment, 조회 index입니다.
 */
public final class ScoreEvaluationContext {

    private final PlanningProblem problem;
    private final RosterSolution solution;
    private final RosterIndex index;
    private final int[] employeeIndexes;
    private final int[] shiftIndexes;

    ScoreEvaluationContext(PlanningProblem problem, RosterSolution solution) {
        this(problem, solution, null, allIndexes(problem.employeeCount()), allIndexes(problem.shiftCount()));
    }

    private ScoreEvaluationContext(
            PlanningProblem problem,
            RosterSolution solution,
            RosterIndex sharedIndex,
            int[] employeeIndexes,
            int[] shiftIndexes) {
        this.problem = Objects.requireNonNull(problem, "problem");
        this.solution = Objects.requireNonNull(solution, "solution");
        if (solution.employeeCount() != problem.employeeCount()) {
            throw new IllegalArgumentException("solution employeeCount가 PlanningProblem과 다릅니다.");
        }
        if (solution.shiftCount() != problem.shiftCount()) {
            throw new IllegalArgumentException("solution shiftCount가 PlanningProblem과 다릅니다.");
        }
        this.index = sharedIndex == null ? new RosterIndex(problem, solution) : sharedIndex;
        this.employeeIndexes = java.util.Arrays.copyOf(employeeIndexes, employeeIndexes.length);
        this.shiftIndexes = java.util.Arrays.copyOf(shiftIndexes, shiftIndexes.length);
    }

    public PlanningProblem problem() {
        return problem;
    }

    public RosterSolution solution() {
        return solution;
    }

    public RosterIndex index() {
        return index;
    }

    public int[] employeeIndexes() {
        return java.util.Arrays.copyOf(employeeIndexes, employeeIndexes.length);
    }

    public int[] shiftIndexes() {
        return java.util.Arrays.copyOf(shiftIndexes, shiftIndexes.length);
    }

    ScoreEvaluationContext forEmployee(int employeeIndex) {
        if (employeeIndex < 0 || employeeIndex >= problem.employeeCount()) {
            throw new IndexOutOfBoundsException("employeeIndex 범위를 벗어났습니다: " + employeeIndex);
        }
        java.util.List<Integer> employeeShifts = index.shiftsByEmployee(employeeIndex);
        int[] scopedShifts = new int[employeeShifts.size()];
        for (int index = 0; index < employeeShifts.size(); index++) {
            scopedShifts[index] = employeeShifts.get(index);
        }
        return new ScoreEvaluationContext(
                problem, solution, this.index, new int[] { employeeIndex }, scopedShifts);
    }

    ScoreEvaluationContext forShift(int shiftIndex) {
        if (shiftIndex < 0 || shiftIndex >= problem.shiftCount()) {
            throw new IndexOutOfBoundsException("shiftIndex 범위를 벗어났습니다: " + shiftIndex);
        }
        return new ScoreEvaluationContext(
                problem,
                solution,
                this.index,
                new int[] { solution.employeeIndex(shiftIndex) },
                new int[] { shiftIndex });
    }

    private static int[] allIndexes(int size) {
        int[] indexes = new int[size];
        for (int index = 0; index < size; index++) {
            indexes[index] = index;
        }
        return indexes;
    }
}
