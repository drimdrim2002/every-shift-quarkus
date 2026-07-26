package org.acme.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
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
        assertEquals(expectedUndesiredSoftScore, solution.getScore().softScore(1),
                "softScore(1) should match actual undesired penalty minutes from the solved schedule");


        int fourConsecutiveNightViolations = countFourConsecutiveNightViolations(solution);
        assertEquals(0, fourConsecutiveNightViolations,
                "4연속 Night 배정은 하드 제약으로 금지되어야 합니다.");
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

    private int countFourConsecutiveNightViolations(EmployeeSchedule schedule) {
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
            for (int i = 0; i <= nightLogicalDates.size() - 4; i++) {
                LocalDate first = nightLogicalDates.get(i);
                LocalDate second = nightLogicalDates.get(i + 1);
                LocalDate third = nightLogicalDates.get(i + 2);
                LocalDate fourth = nightLogicalDates.get(i + 3);
                if (second.equals(first.plusDays(1))
                        && third.equals(first.plusDays(2))
                        && fourth.equals(first.plusDays(3))) {
                    violations++;
                }
            }
        }

        return violations;
    }

    private boolean isNightShift(Shift shift) {
        return "N".equals(normalizeShiftCode(shift));
    }

    private String normalizeShiftCode(Shift shift) {
        if (shift.getShiftCode() == null) {
            return "";
        }
        return shift.getShiftCode().trim().toUpperCase(Locale.ROOT);
    }

    @Test
    public void testPreceptorConstraint() throws IOException {
        // Load preceptor.json content
        String jsonInput = JsonLoader.loadAsString("/json/preceptor.json");

        // Run the solver
        EmployeeSchedule solution = solverRunner.runWithResult(jsonInput);

        assertNotNull(solution.getScore(), "Score should not be null");
        // Verify Hard Score is 0 (all hard constraints satisfied)
        assertEquals(0, solution.getScore().hardScore(),
                "All hard constraints including preceptor matching must be satisfied");

        // Verify preceptor matching
        Map<String, org.acme.model.Employee> employeeMap = solution.getEmployeeList().stream()
                .collect(Collectors.toMap(org.acme.model.Employee::getId, e -> e));

        Map<String, List<Shift>> shiftsByEmployeeId = solution.getShiftList().stream()
                .filter(shift -> shift.getEmployee() != null)
                .collect(Collectors.groupingBy(shift -> shift.getEmployee().getId()));

        for (org.acme.model.Employee employee : solution.getEmployeeList()) {
            if (employee.getPreceptorId() != null) {
                org.acme.model.Employee preceptor = employeeMap.get(employee.getPreceptorId());
                assertNotNull(preceptor, "Preceptor should exist in the employee list");

                List<Shift> precepteeShifts = shiftsByEmployeeId.getOrDefault(employee.getId(), List.of());
                List<Shift> preceptorShifts = shiftsByEmployeeId.getOrDefault(preceptor.getId(), List.of());

                // Map of Date -> ShiftCode for both
                Map<LocalDate, String> precepteeMap = precepteeShifts.stream()
                        .collect(Collectors.toMap(s -> s.getStart().toLocalDate(), Shift::getShiftCode));
                Map<LocalDate, String> preceptorMap = preceptorShifts.stream()
                        .collect(Collectors.toMap(s -> s.getStart().toLocalDate(), Shift::getShiftCode));

                // All preceptee shifts must have a matching preceptor shift
                for (Map.Entry<LocalDate, String> entry : precepteeMap.entrySet()) {
                    assertEquals(entry.getValue(), preceptorMap.get(entry.getKey()),
                            String.format("Preceptor %s and Preceptee %s must work the same shift on %s",
                                    preceptor.getName(), employee.getName(), entry.getKey()));
                }

                // All preceptor shifts must have a matching preceptee shift
                for (Map.Entry<LocalDate, String> entry : preceptorMap.entrySet()) {
                    assertEquals(entry.getValue(), precepteeMap.get(entry.getKey()),
                            String.format("Preceptor %s and Preceptee %s must work the same shift on %s",
                                    preceptor.getName(), employee.getName(), entry.getKey()));
                }
            }
        }
    }
}
