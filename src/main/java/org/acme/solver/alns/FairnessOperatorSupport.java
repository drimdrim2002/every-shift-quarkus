package org.acme.solver.alns;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.score.RosterIndex;

/**
 * ALNS operator가 soft[2] 형평성 산식과 같은 제곱 부담을 읽기 위한 보조 계산입니다.
 *
 * <p>미배정(-1)은 제외하며, complete/partial 양쪽에서 같은 함수를 사용합니다.</p>
 */
final class FairnessOperatorSupport {

    private FairnessOperatorSupport() {
    }

    static long employeePenalty(
            PlanningProblem problem,
            IntUnaryOperator assignment,
            int employeeIndex) {
        return snapshot(problem, assignment).employeePenalty(employeeIndex);
    }

    static long removalGain(
            PlanningProblem problem,
            IntUnaryOperator assignment,
            int shiftIndex) {
        return snapshot(problem, assignment).removalGain(shiftIndex);
    }

    static long insertionPenalty(
            PlanningProblem problem,
            IntUnaryOperator assignment,
            int shiftIndex,
            int employeeIndex) {
        return snapshot(problem, assignment).insertionPenalty(shiftIndex, employeeIndex);
    }

    static Snapshot snapshot(
            PlanningProblem problem,
            IntUnaryOperator assignment) {
        int employeeCount = problem.employeeCount();
        int[] assignments = new int[problem.shiftCount()];
        long[] nightBurden = new long[employeeCount];
        long[] holidayBurden = new long[employeeCount];
        List<Map<String, Long>> nonNightCounts = new java.util.ArrayList<>(employeeCount);
        for (int employeeIndex = 0; employeeIndex < employeeCount; employeeIndex++) {
            nonNightCounts.add(new LinkedHashMap<>());
        }

        for (int shiftIndex = 0; shiftIndex < problem.shiftCount(); shiftIndex++) {
            int employeeIndex = assignment.applyAsInt(shiftIndex);
            assignments[shiftIndex] = employeeIndex;
            if (employeeIndex < 0) {
                continue;
            }
            PlanningProblem.ShiftData shift = problem.shifts().get(shiftIndex);
            String shiftType = RosterIndex.normalizeShiftType(shift.shiftCode());
            if (RosterIndex.SHIFT_TYPE_NIGHT.equals(shiftType)) {
                nightBurden[employeeIndex] = Math.addExact(
                        nightBurden[employeeIndex], shift.nightBurdenScore());
            } else {
                nonNightCounts.get(employeeIndex).merge(shiftType, 1L, Math::addExact);
            }
            if (shift.holidayBurdenScore() > 0) {
                holidayBurden[employeeIndex] = Math.addExact(
                        holidayBurden[employeeIndex], shift.holidayBurdenScore());
            }
        }
        return new Snapshot(problem, assignments, nightBurden, holidayBurden, nonNightCounts);
    }

    private static long penalty(
            long nightBurden,
            long holidayBurden,
            Map<String, Long> nonNightCounts) {
        long penalty = square(nightBurden);
        penalty = Math.addExact(penalty, square(holidayBurden));
        for (Map.Entry<String, Long> entry : nonNightCounts.entrySet()) {
            long weight = RosterIndex.SHIFT_TYPE_EVENING.equals(entry.getKey()) ? 5L : 1L;
            penalty = Math.addExact(
                    penalty,
                    Math.multiplyExact(weight, square(entry.getValue())));
        }
        return penalty;
    }

    private static long square(long value) {
        return Math.multiplyExact(value, value);
    }

    static final class Snapshot {

        private final PlanningProblem problem;
        private final int[] assignments;
        private final long[] nightBurden;
        private final long[] holidayBurden;
        private final List<Map<String, Long>> nonNightCounts;

        private Snapshot(
                PlanningProblem problem,
                int[] assignments,
                long[] nightBurden,
                long[] holidayBurden,
                List<Map<String, Long>> nonNightCounts) {
            this.problem = problem;
            this.assignments = assignments;
            this.nightBurden = nightBurden;
            this.holidayBurden = holidayBurden;
            this.nonNightCounts = nonNightCounts;
        }

        long employeePenalty(int employeeIndex) {
            return penalty(
                    nightBurden[employeeIndex],
                    holidayBurden[employeeIndex],
                    nonNightCounts.get(employeeIndex));
        }

        long removalGain(int shiftIndex) {
            int employeeIndex = assignments[shiftIndex];
            if (employeeIndex < 0) {
                throw new IllegalArgumentException(
                        "미배정 shift의 제거 이득은 계산할 수 없습니다: " + shiftIndex);
            }
            PlanningProblem.ShiftData shift = problem.shifts().get(shiftIndex);
            String shiftType = RosterIndex.normalizeShiftType(shift.shiftCode());
            long gain = 0L;
            if (RosterIndex.SHIFT_TYPE_NIGHT.equals(shiftType)) {
                gain = Math.addExact(
                        gain,
                        removalGain(nightBurden[employeeIndex], shift.nightBurdenScore()));
            } else {
                long count = nonNightCounts.get(employeeIndex).getOrDefault(shiftType, 0L);
                long weight = RosterIndex.SHIFT_TYPE_EVENING.equals(shiftType) ? 5L : 1L;
                gain = Math.addExact(gain, Math.multiplyExact(weight, removalGain(count, 1L)));
            }
            if (shift.holidayBurdenScore() > 0) {
                gain = Math.addExact(
                        gain,
                        removalGain(holidayBurden[employeeIndex], shift.holidayBurdenScore()));
            }
            return gain;
        }

        long insertionPenalty(int shiftIndex, int employeeIndex) {
            if (assignments[shiftIndex] >= 0) {
                throw new IllegalArgumentException(
                        "이미 배정된 shift의 삽입 비용은 계산할 수 없습니다: " + shiftIndex);
            }
            PlanningProblem.ShiftData shift = problem.shifts().get(shiftIndex);
            String shiftType = RosterIndex.normalizeShiftType(shift.shiftCode());
            long added = 0L;
            if (RosterIndex.SHIFT_TYPE_NIGHT.equals(shiftType)) {
                added = Math.addExact(
                        added,
                        insertionPenalty(nightBurden[employeeIndex], shift.nightBurdenScore()));
            } else {
                long count = nonNightCounts.get(employeeIndex).getOrDefault(shiftType, 0L);
                long weight = RosterIndex.SHIFT_TYPE_EVENING.equals(shiftType) ? 5L : 1L;
                added = Math.addExact(
                        added,
                        Math.multiplyExact(weight, insertionPenalty(count, 1L)));
            }
            if (shift.holidayBurdenScore() > 0) {
                added = Math.addExact(
                        added,
                        insertionPenalty(holidayBurden[employeeIndex], shift.holidayBurdenScore()));
            }
            return added;
        }

        private static long removalGain(long burden, long removed) {
            return Math.subtractExact(square(burden), square(Math.subtractExact(burden, removed)));
        }

        private static long insertionPenalty(long burden, long inserted) {
            return Math.subtractExact(square(Math.addExact(burden, inserted)), square(burden));
        }
    }
}
