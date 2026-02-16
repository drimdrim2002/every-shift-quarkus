package org.acme.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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

        int nightToDayViolations = countNightToDayBufferViolations(solution);
        assertEquals(0, nightToDayViolations,
                "Draft 범위에서 Night 이후 1~2일 내 Day 배정은 없어야 합니다.");
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

            int shiftDurationMinutes = (int) Duration.between(shift.getStart(), shift.getEnd()).toMinutes();
            for (LocalDate undesiredDate : undesiredDates) {
                if (ShiftDateMatcher.matchesActualOrLogicalDate(shift, undesiredDate)) {
                    penaltyMinutes += shiftDurationMinutes;
                }
            }
        }

        return -penaltyMinutes;
    }

    private int countNightToDayBufferViolations(EmployeeSchedule schedule) {
        if (schedule.getShiftList() == null) {
            return 0;
        }

        Map<String, List<Shift>> shiftsByEmployeeId = schedule.getShiftList().stream()
                .filter(shift -> shift.getEmployee() != null)
                .collect(Collectors.groupingBy(shift -> shift.getEmployee().getId()));

        int violations = 0;
        for (List<Shift> shifts : shiftsByEmployeeId.values()) {
            List<Shift> draftShifts = shifts.stream()
                    .filter(shift -> !shift.isPinned())
                    .toList();

            List<Shift> nightShifts = draftShifts.stream()
                    .filter(this::isNightShift)
                    .toList();

            List<Shift> dayShifts = draftShifts.stream()
                    .filter(this::isDayShift)
                    .toList();

            for (Shift nightShift : nightShifts) {
                LocalDate nightLogicalDate = nightShift.getStart().toLocalDate().minusDays(1);
                for (Shift dayShift : dayShifts) {
                    long dayGap = ChronoUnit.DAYS.between(nightLogicalDate, dayShift.getStart().toLocalDate());
                    if (dayGap >= 1 && dayGap <= 2) {
                        violations++;
                    }
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
