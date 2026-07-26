package org.acme.solver.alns;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.random.RandomGenerator;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.move.PreceptorRelationIndex;

/** complete current의 읽기 전용 destroy SPI context입니다. */
public final class DestroyContext {

    private final PlanningProblem problem;
    private final int[] assignments;
    private final int[] mutableShiftIndexes;
    private final PreceptorRelationIndex relationIndex;
    private final DestroySize destroySize;
    private final RandomGenerator random;
    private boolean active = true;

    DestroyContext(
            PlanningProblem problem,
            int[] completeAssignments,
            DestroySize destroySize,
            RandomGenerator random) {
        this.problem = Objects.requireNonNull(problem, "problem");
        this.assignments = Arrays.copyOf(completeAssignments, completeAssignments.length);
        if (assignments.length != problem.shiftCount()) {
            throw new IllegalArgumentException("assignment와 problem의 shift 수가 다릅니다.");
        }
        for (int shiftIndex = 0; shiftIndex < assignments.length; shiftIndex++) {
            int employeeIndex = assignments[shiftIndex];
            if (employeeIndex < 0 || employeeIndex >= problem.employeeCount()) {
                throw new IllegalArgumentException("destroy context는 complete current만 받습니다: " + shiftIndex);
            }
        }
        this.mutableShiftIndexes = problem.mutableShiftIndexes();
        this.relationIndex = new PreceptorRelationIndex(problem);
        this.destroySize = Objects.requireNonNull(destroySize, "destroySize");
        this.random = Objects.requireNonNull(random, "random");
    }

    public PlanningProblem problem() {
        ensureActive();
        return problem;
    }

    public int requestedRemovalCount() {
        ensureActive();
        return destroySize.requestedRemovalCount();
    }

    public int actualRemovalLimit() {
        ensureActive();
        return destroySize.actualRemovalLimit();
    }

    /** immutable shift는 이 후보 집합을 만들 때부터 제외됩니다. */
    public int[] mutableShiftIndexes() {
        ensureActive();
        return Arrays.copyOf(mutableShiftIndexes, mutableShiftIndexes.length);
    }

    public int employeeIndex(int shiftIndex) {
        ensureActive();
        return assignments[shiftIndex];
    }

    public List<Integer> relationEmployeesFor(int employeeIndex) {
        ensureActive();
        return relationIndex.employeesFor(employeeIndex);
    }

    public RandomGenerator random() {
        ensureActive();
        return random;
    }

    /** seed shift와 같은 실제일·교대에 있는 relation group의 mutable assignment를 확장합니다. */
    public int[] relationBundle(int seedShiftIndex) {
        ensureActive();
        if (!problem.isMutableShift(seedShiftIndex)) {
            throw new IllegalArgumentException("immutable shift는 relation bundle seed가 될 수 없습니다: " + seedShiftIndex);
        }
        PlanningProblem.ShiftData seed = problem.shifts().get(seedShiftIndex);
        LocalDate date = seed.start().toLocalDate();
        String shiftCode = normalize(seed.shiftCode());
        List<Integer> relationEmployees = relationIndex.employeesFor(assignments[seedShiftIndex]);
        List<Integer> bundle = new ArrayList<>();
        for (int shiftIndex : mutableShiftIndexes) {
            PlanningProblem.ShiftData candidate = problem.shifts().get(shiftIndex);
            if (candidate.start().toLocalDate().equals(date)
                    && normalize(candidate.shiftCode()).equals(shiftCode)
                    && relationEmployees.contains(assignments[shiftIndex])) {
                bundle.add(shiftIndex);
            }
        }
        if (!bundle.contains(seedShiftIndex)) {
            throw new IllegalStateException("relation bundle이 seed shift를 포함하지 않습니다.");
        }
        return bundle.stream().mapToInt(Integer::intValue).toArray();
    }

    void close() {
        active = false;
    }

    private void ensureActive() {
        if (!active) {
            throw new IllegalStateException("destroy context는 iteration 밖에서 사용할 수 없습니다.");
        }
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
