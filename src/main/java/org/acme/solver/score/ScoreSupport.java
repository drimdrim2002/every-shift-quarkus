package org.acme.solver.score;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.acme.solver.core.PlanningProblem;

final class ScoreSupport {

    private ScoreSupport() {
    }

    static boolean isNight(PlanningProblem.ShiftData shift) {
        return RosterIndex.SHIFT_TYPE_NIGHT.equals(RosterIndex.normalizeShiftType(shift.shiftCode()));
    }

    static boolean isDay(PlanningProblem.ShiftData shift) {
        return RosterIndex.SHIFT_TYPE_DAY.equals(RosterIndex.normalizeShiftType(shift.shiftCode()));
    }

    static int durationMinutes(PlanningProblem.ShiftData shift) {
        return Math.toIntExact(Duration.between(shift.start(), shift.end()).toMinutes());
    }

    static int minutesBetween(java.time.LocalDateTime from, java.time.LocalDateTime to) {
        return Math.toIntExact(Duration.between(from, to).toMinutes());
    }

    static int breakMinutes(PlanningProblem.ShiftData first, PlanningProblem.ShiftData second) {
        if (!first.end().isAfter(second.start())) {
            return minutesBetween(first.end(), second.start());
        }
        if (!second.end().isAfter(first.start())) {
            return minutesBetween(second.end(), first.start());
        }
        return -1;
    }

    static int overlapMinutes(PlanningProblem.ShiftData first, PlanningProblem.ShiftData second) {
        java.time.LocalDateTime start = first.start().isAfter(second.start()) ? first.start() : second.start();
        java.time.LocalDateTime end = first.end().isBefore(second.end()) ? first.end() : second.end();
        return minutesBetween(start, end);
    }

    static List<Integer> nightShiftsOnLogicalDate(
            ScoreEvaluationContext context, int employeeIndex, LocalDate logicalDate) {
        List<Integer> result = new ArrayList<>();
        for (int shiftIndex : context.index().shiftsByLogicalDate(employeeIndex, logicalDate)) {
            if (isNight(context.problem().shifts().get(shiftIndex))) {
                result.add(shiftIndex);
            }
        }
        return result;
    }

    static List<Integer> pair(int first, int second) {
        return List.of(first, second);
    }
}
