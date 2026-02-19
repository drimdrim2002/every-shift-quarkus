package org.acme.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.acme.model.AvailabilityType;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.acme.test.JsonLoader;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
public class SolverRunnerTest {

    private static final long MIN_NIGHT_TO_NEXT_DAY_REST_MINUTES = 32L * 60L;

    @Inject
    SolverRunner solverRunner;

    @Test
    public void testRun() throws IOException {
        // Load sample.json content
        String jsonInput = JsonLoader.loadAsString("/json/request.json");

        // Run the solver
        EmployeeSchedule solution = solverRunner.runWithResult(jsonInput);

        assertNotNull(solution.getScore(), "Score should not be null");

        int expectedUndesiredSoftScore = calculateUndesiredSoftScore(solution);
        assertEquals(expectedUndesiredSoftScore, solution.getScore().softScore(0),
                "softScore(0) should match actual undesired penalty minutes from the solved schedule");

        int threeConsecutiveNightViolations = countThreeConsecutiveNightViolations(solution);
        assertEquals(0, threeConsecutiveNightViolations,
                "3연속 Night 배정은 없어야 합니다.");

        int nightToNextDayMinimum32HourViolations = countNightToNextDayMinimum32HourViolations(solution);
        assertEquals(0, nightToNextDayMinimum32HourViolations,
                "Night 종료 후 다음 Day 시작까지 최소 32시간이 보장되어야 합니다.");
    }

    private int calculateUndesiredSoftScore(EmployeeSchedule schedule) {
        Map<String, Set<LocalDate>> undesiredDatesByEmployeeId = new HashMap<>();
        if (schedule.getAvailabilityList() != null) {
            schedule.getAvailabilityList().stream()
                    .filter(availability -> availability.getEmployee() != null)
                    .filter(availability -> availability.getAvailabilityType() == AvailabilityType.UNDESIRED)
                    .forEach(availability -> undesiredDatesByEmployeeId
                            .computeIfAbsent(availability.getEmployee().getId(), key -> new HashSet<>())
                            .add(availability.getDate()));
        }

        int penaltyMinutes = 0;
        for (Shift shift : schedule.getShiftList()) {
            if (shift.isPinned() || shift.getEmployee() == null) {
                continue;
            }

            Set<LocalDate> undesiredDates = undesiredDatesByEmployeeId.get(shift.getEmployee().getId());
            if (undesiredDates == null) {
                continue;
            }

            boolean hasUndesiredMatch = undesiredDates.stream()
                    .anyMatch(undesiredDate -> ShiftDateMatcher.matchesActualOrLogicalDate(shift, undesiredDate));
            if (hasUndesiredMatch) {
                int shiftDurationMinutes = (int) Duration.between(shift.getStart(), shift.getEnd()).toMinutes();
                penaltyMinutes += shiftDurationMinutes;
            }
        }

        return -penaltyMinutes;
    }

    private int countThreeConsecutiveNightViolations(EmployeeSchedule schedule) {
        if (schedule.getShiftList() == null) {
            return 0;
        }

        Map<String, List<Shift>> shiftsByEmployeeId = schedule.getShiftList().stream()
                .filter(shift -> shift.getEmployee() != null)
                .collect(Collectors.groupingBy(shift -> shift.getEmployee().getId()));

        int violations = 0;
        for (List<Shift> shifts : shiftsByEmployeeId.values()) {
            List<LocalDate> nightLogicalDates = shifts.stream()
                    .filter(this::isNightShift)
                    .map(ShiftDateMatcher::resolveLogicalDate)
                    .sorted()
                    .toList();
            for (int i = 0; i <= nightLogicalDates.size() - 3; i++) {
                LocalDate first = nightLogicalDates.get(i);
                LocalDate second = nightLogicalDates.get(i + 1);
                LocalDate third = nightLogicalDates.get(i + 2);
                if (second.equals(first.plusDays(1)) && third.equals(first.plusDays(2))) {
                    violations++;
                }
            }
        }

        return violations;
    }

    private int countNightToNextDayMinimum32HourViolations(EmployeeSchedule schedule) {
        if (schedule.getShiftList() == null) {
            return 0;
        }

        Map<String, List<Shift>> shiftsByEmployeeId = schedule.getShiftList().stream()
                .filter(shift -> shift.getEmployee() != null)
                .collect(Collectors.groupingBy(shift -> shift.getEmployee().getId()));

        int violations = 0;
        for (List<Shift> shifts : shiftsByEmployeeId.values()) {
            List<Shift> dayShifts = shifts.stream()
                    .filter(this::isDayShift)
                    .sorted(Comparator.comparing(Shift::getStart))
                    .toList();
            List<Shift> nightShifts = shifts.stream()
                    .filter(this::isNightShift)
                    .toList();

            for (Shift nightShift : nightShifts) {
                Shift nextDayShift = dayShifts.stream()
                        .filter(dayShift -> dayShift.getStart().isAfter(nightShift.getEnd()))
                        .findFirst()
                        .orElse(null);
                if (nextDayShift == null) {
                    continue;
                }

                long restMinutes = Duration.between(nightShift.getEnd(), nextDayShift.getStart()).toMinutes();
                if (restMinutes < MIN_NIGHT_TO_NEXT_DAY_REST_MINUTES) {
                    violations++;
                }
            }
        }

        return violations;
    }

    private boolean isNightShift(Shift shift) {
        return "N".equals(normalizeShiftCode(shift));
    }

    private boolean isDayShift(Shift shift) {
        return "D".equals(normalizeShiftCode(shift));
    }

    private String normalizeShiftCode(Shift shift) {
        if (shift.getShiftCode() == null) {
            return "";
        }
        return shift.getShiftCode().trim().toUpperCase(Locale.ROOT);
    }
}
