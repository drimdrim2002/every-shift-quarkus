package org.acme.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
            if (shift.getEmployee() == null) {
                continue;
            }

            Set<LocalDate> undesiredDates = undesiredDatesByEmployeeId.get(shift.getEmployee().getId());
            if (undesiredDates == null) {
                continue;
            }

            if (undesiredDates.contains(shift.getStart().toLocalDate())) {
                penaltyMinutes += (int) Duration.between(shift.getStart(), shift.getEnd()).toMinutes();
            }
        }

        return -penaltyMinutes;
    }
}
