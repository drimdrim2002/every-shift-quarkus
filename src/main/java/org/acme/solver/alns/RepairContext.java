package org.acme.solver.alns;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.move.PreceptorRelationIndex;

/**
 * repair SPI에 제공되는 제한된 partial view입니다.
 * 점수, complete snapshot, best/callback 접근을 제공하지 않습니다.
 */
public final class RepairContext {

    private static final long SAME_DAY_PENALTY = 1_000_000_000L;
    private static final long OVERLAP_PENALTY = 1_000_000_000L;
    private static final long RELATION_CONFLICT_PENALTY = 100_000_000L;
    private static final long MINIMUM_REST_MINUTES = 12L * 60L;

    private final PlanningProblem problem;
    private final PartialSolution partial;
    private final PreceptorRelationIndex relationIndex;
    private final RandomGenerator random;
    private final int maxAttempts;
    private int attempts;
    private FairnessOperatorSupport.Snapshot fairnessSnapshot;
    private boolean active = true;

    RepairContext(
            PlanningProblem problem,
            PartialSolution partial,
            RandomGenerator random,
            int maxAttempts) {
        this.problem = Objects.requireNonNull(problem, "problem");
        this.partial = Objects.requireNonNull(partial, "partial");
        this.random = Objects.requireNonNull(random, "random");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts는 1 이상이어야 합니다.");
        }
        this.maxAttempts = maxAttempts;
        this.relationIndex = new PreceptorRelationIndex(problem);
    }

    public PlanningProblem problem() {
        ensureActive();
        return problem;
    }

    /** 새 repair 시도를 시작하고 partial을 destroy 직후 상태로 되돌립니다. */
    public boolean beginAttempt() {
        ensureActive();
        if (attempts >= maxAttempts) {
            return false;
        }
        attempts++;
        partial.reset();
        fairnessSnapshot = null;
        return true;
    }

    public int attempts() {
        ensureActive();
        return attempts;
    }

    public int maxAttempts() {
        ensureActive();
        return maxAttempts;
    }

    public int[] unassignedShiftIndexes() {
        ensureActive();
        return partial.unassignedShiftIndexes();
    }

    public int assignment(int shiftIndex) {
        ensureActive();
        return partial.assignment(shiftIndex);
    }

    public int originalEmployeeIndex(int shiftIndex) {
        ensureActive();
        return partial.originalAssignment(shiftIndex);
    }

    public void assign(int shiftIndex, int employeeIndex) {
        ensureActive();
        if (attempts == 0) {
            throw new IllegalStateException("beginAttempt() 뒤에만 repair할 수 있습니다.");
        }
        partial.assign(shiftIndex, employeeIndex);
        fairnessSnapshot = null;
    }

    public boolean isComplete() {
        ensureActive();
        return partial.isComplete();
    }

    /** skill과 shift-code가 호환되는 직원만 안정 index 순서로 반환합니다. */
    public int[] candidateEmployeeIndexes(int shiftIndex) {
        ensureActive();
        PlanningProblem.ShiftData shift = problem.shifts().get(shiftIndex);
        List<Integer> result = new ArrayList<>();
        String requiredCode = DestroyContext.normalize(shift.shiftCode());
        for (int employeeIndex = 0; employeeIndex < problem.employeeCount(); employeeIndex++) {
            PlanningProblem.EmployeeData employee = problem.employees().get(employeeIndex);
            boolean skillCompatible = employee.skillSet().contains(shift.requiredSkill());
            boolean codeCompatible = employee.availableShiftCodes().stream()
                    .map(DestroyContext::normalize)
                    .anyMatch(requiredCode::equals);
            if (skillCompatible && codeCompatible) {
                result.add(employeeIndex);
            }
        }
        if (result.isEmpty()) {
            result.add(partial.originalAssignment(shiftIndex));
        }
        return result.stream().mapToInt(Integer::intValue).toArray();
    }

    /** RosterScore가 아닌 repair 전용 지역 삽입 비용입니다. 낮을수록 우선합니다. */
    public long insertionCost(int shiftIndex, int employeeIndex) {
        ensureActive();
        if (partial.assignment(shiftIndex) >= 0) {
            throw new IllegalStateException("삽입 비용은 아직 미배정인 shift에만 계산할 수 있습니다.");
        }
        PlanningProblem.ShiftData target = problem.shifts().get(shiftIndex);
        long cost = 0L;
        int assignedCount = 0;
        for (int otherShiftIndex = 0; otherShiftIndex < problem.shiftCount(); otherShiftIndex++) {
            if (partial.assignment(otherShiftIndex) != employeeIndex) {
                continue;
            }
            assignedCount++;
            PlanningProblem.ShiftData other = problem.shifts().get(otherShiftIndex);
            if (other.start().toLocalDate().equals(target.start().toLocalDate())) {
                cost = addSaturated(cost, SAME_DAY_PENALTY);
            }
            if (target.start().isBefore(other.end()) && other.start().isBefore(target.end())) {
                cost = addSaturated(cost, OVERLAP_PENALTY);
            } else {
                long restMinutes = restMinutes(target, other);
                if (restMinutes >= 0L && restMinutes < MINIMUM_REST_MINUTES) {
                    cost = addSaturated(cost, (MINIMUM_REST_MINUTES - restMinutes) * 10_000L);
                }
            }
        }
        cost = addSaturated(cost, assignedCount * 10L);

        LocalDate targetDate = target.start().toLocalDate();
        for (PlanningProblem.AvailabilityData availability : problem.availabilities()) {
            if (availability.employeeIndex() == employeeIndex && availability.date().equals(targetDate)) {
                cost = addSaturated(cost,
                        availability.kind() == PlanningProblem.AvailabilityKind.UNDESIRED ? 100_000L : -10_000L);
            }
        }

        String targetCode = DestroyContext.normalize(target.shiftCode());
        for (int relatedEmployee : relationIndex.employeesFor(employeeIndex)) {
            if (relatedEmployee == employeeIndex) {
                continue;
            }
            for (int otherShiftIndex = 0; otherShiftIndex < problem.shiftCount(); otherShiftIndex++) {
                if (partial.assignment(otherShiftIndex) != relatedEmployee) {
                    continue;
                }
                PlanningProblem.ShiftData other = problem.shifts().get(otherShiftIndex);
                if (!other.start().toLocalDate().equals(targetDate)) {
                    continue;
                }
                cost = addSaturated(cost,
                        DestroyContext.normalize(other.shiftCode()).equals(targetCode)
                                ? -10_000L
                                : RELATION_CONFLICT_PENALTY);
            }
        }
        return cost;
    }

    /**
     * 기존 hard/관계/선호 지역 비용에 soft[2]와 동일한 제곱 형평성 삽입 한계 비용을 더합니다.
     *
     * <p>complete score가 아니라 test candidate repair의 정렬 비용입니다.</p>
     */
    public long fairnessAwareInsertionCost(int shiftIndex, int employeeIndex) {
        ensureActive();
        return addSaturated(
                insertionCost(shiftIndex, employeeIndex),
                fairnessSnapshot().insertionPenalty(shiftIndex, employeeIndex));
    }

    public int[] candidatesByInsertionCost(int shiftIndex) {
        ensureActive();
        return Arrays.stream(candidateEmployeeIndexes(shiftIndex))
                .boxed()
                .sorted(Comparator
                        .comparingLong((Integer employeeIndex) -> insertionCost(shiftIndex, employeeIndex))
                        .thenComparingInt(Integer::intValue))
                .mapToInt(Integer::intValue)
                .toArray();
    }

    public List<Integer> relationEmployeesFor(int employeeIndex) {
        ensureActive();
        return relationIndex.employeesFor(employeeIndex);
    }

    public RandomGenerator random() {
        ensureActive();
        return random;
    }

    int[] completeAssignments() {
        ensureActive();
        return partial.completeAssignments();
    }

    void close() {
        active = false;
    }

    private void ensureActive() {
        if (!active) {
            throw new IllegalStateException("repair context는 iteration 밖에서 사용할 수 없습니다.");
        }
    }

    private FairnessOperatorSupport.Snapshot fairnessSnapshot() {
        if (fairnessSnapshot == null) {
            fairnessSnapshot = FairnessOperatorSupport.snapshot(problem, partial::assignment);
        }
        return fairnessSnapshot;
    }

    private static long restMinutes(
            PlanningProblem.ShiftData left, PlanningProblem.ShiftData right) {
        if (!left.end().isAfter(right.start())) {
            return Duration.between(left.end(), right.start()).toMinutes();
        }
        if (!right.end().isAfter(left.start())) {
            return Duration.between(right.end(), left.start()).toMinutes();
        }
        return -1L;
    }

    private static long addSaturated(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return right >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }
}
