package org.acme.solver;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.acme.api.dto.PlanningRequest;
import org.acme.model.Availability;
import org.acme.model.AvailabilityType;
import org.acme.model.Employee;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.acme.solver.algorithm.EmployeeSchedulingConstraintProvider;
import org.acme.util.DtoConverter;
import org.acme.util.ScheduleExporter;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.config.solver.EnvironmentMode;
import org.optaplanner.core.config.solver.SolverConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class SolverRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SolverRunner.class);

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "solver.termination.spent-limit", defaultValue = "10")
    long spentLimit;

    @ConfigProperty(name = "solver.move-thread-count", defaultValue = "AUTO")
    String moveThreadCount;

    @ConfigProperty(name = "solver.environment-mode", defaultValue = "REPRODUCIBLE")
    EnvironmentMode environmentMode;

    @ConfigProperty(name = "solver.random-seed", defaultValue = "0")
    long randomSeed;

    @ConfigProperty(name = "app.export.output-dir", defaultValue = "/tmp/schedule-output")
    String exportOutputDir;

    @ConfigProperty(name = "app.export.enabled", defaultValue = "true")
    boolean exportEnabled;

    public void run(String jsonInput) {
        LOG.info("--- Solver calculation started ---");

        try {
            // 1. Parse JSON
            PlanningRequest request = objectMapper.readValue(jsonInput, PlanningRequest.class);
            LOG.info("Organization: {}", request.organization().name());

            // 2. Solve
            EmployeeSchedule solution = solve(request);

            // 3. Output
            HardSoftScore score = solution.getScore();

            validateSolution(solution);

            LOG.info("Score: {}", score);
            printSchedule(solution);

            // Markdown export
            if (exportEnabled) {
                try {
                    String outputPath = ScheduleExporter.exportToMarkdown(solution, exportOutputDir);
                    LOG.info("Schedule exported to: {}", outputPath);
                } catch (Exception e) {
                    LOG.warn("Failed to export schedule to markdown: {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            LOG.error("Solving failed", e);
            throw new RuntimeException(e);
        }

        LOG.info("--- Solver calculation ended ---");
    }

    public EmployeeSchedule solve(PlanningRequest request) {
        // 1. Convert to Domain Model
        EmployeeSchedule problem = DtoConverter.toEmployeeSchedule(request);

        // 2. Build Solver
        SolverFactory<EmployeeSchedule> solverFactory = SolverFactory.create(new SolverConfig()
                .withSolutionClass(EmployeeSchedule.class)
                .withEntityClasses(Shift.class)
                .withConstraintProviderClass(EmployeeSchedulingConstraintProvider.class)
                .withTerminationSpentLimit(Duration.ofSeconds(spentLimit))
                .withMoveThreadCount(moveThreadCount)
                .withEnvironmentMode(environmentMode)
                .withRandomSeed(randomSeed));

        Solver<EmployeeSchedule> solver = solverFactory.buildSolver();

        // 3. Solve
        return solver.solve(problem);
    }

    private void printSchedule(EmployeeSchedule schedule) {
        LOG.info("\n--- Schedule ---");
        Map<LocalDate, List<Shift>> shiftsByDate = schedule.getShiftList().stream()
                .collect(Collectors.groupingBy(shift -> shift.getStart().toLocalDate()));

        shiftsByDate.keySet().stream().sorted().forEach(date -> {
            LOG.info("Date: {}", date);
            for (Shift shift : shiftsByDate.get(date)) {
                LOG.info("  {} - {} ({}): {}",
                        shift.getStart().toLocalTime(),
                        shift.getEnd().toLocalTime(),
                        shift.getLocation(),
                        shift.getEmployee() == null ? "UNASSIGNED" : shift.getEmployee().getName());
            }
        });
    }

    private void validateSolution(EmployeeSchedule schedule) {
        LOG.info("=== Solution Validation Started ===");

        // Phase 1: 데이터 무결성 검증
        validateDataIntegrity(schedule);

        // Phase 2: 직원별 그룹화
        Map<Employee, List<Shift>> shiftsByEmployee = groupShiftsByEmployee(schedule);

        // Phase 3: Hard Constraint 검증
        validateRequiredSkills(schedule);
        validateNoOverlappingShifts(shiftsByEmployee);
        validateOneShiftPerDay(shiftsByEmployee);
        validateEmployeeAvailability(schedule, shiftsByEmployee);

        // Phase 4: 핵심 - 동시성 검증
        validateSimultaneousWorkAtDifferentLocations(shiftsByEmployee);

        LOG.info("=== Solution Validation Completed - No Violations Found ===");
    }

    // ========== Helper Methods ==========

    private Map<Employee, List<Shift>> groupShiftsByEmployee(EmployeeSchedule schedule) {
        Map<Employee, List<Shift>> result = new HashMap<>();
        for (Shift shift : schedule.getShiftList()) {
            if (shift.getEmployee() != null) {
                result.computeIfAbsent(shift.getEmployee(), k -> new ArrayList<>()).add(shift);
            }
        }
        return result;
    }

    private boolean hasTimeOverlap(Shift shift1, Shift shift2) {
        return EmployeeSchedulingConstraintProvider.getMinuteOverlap(shift1, shift2) > 0;
    }

    private void throwViolation(String message, Object... args) {
        String formattedMessage = String.format(message, args);
        LOG.error("Validation failed: {}", formattedMessage);
        throw new RuntimeException("Validation failed: " + formattedMessage);
    }

    // ========== Validation Methods ==========

    private void validateDataIntegrity(EmployeeSchedule schedule) {
        for (Shift shift : schedule.getShiftList()) {
            // Null employee check
            if (shift.getEmployee() == null) {
                throwViolation(
                        "Shift %d has no employee assigned (start: %s, end: %s, location: %s)",
                        shift.getId(), shift.getStart(), shift.getEnd(), shift.getLocation());
            }

            // Time logic check
            if (shift.getStart().isAfter(shift.getEnd())) {
                throwViolation(
                        "Shift %d has invalid time range: start %s is after end %s",
                        shift.getId(), shift.getStart(), shift.getEnd());
            }
        }
    }

    private void validateRequiredSkills(EmployeeSchedule schedule) {
        for (Shift shift : schedule.getShiftList()) {
            Employee employee = shift.getEmployee();
            if (employee != null && !employee.getSkillSet().contains(shift.getRequiredSkill())) {
                throwViolation(
                        "Employee '%s' lacks required skill '%s' for shift %d at %s (%s-%s)",
                        employee.getName(), shift.getRequiredSkill(),
                        shift.getId(), shift.getLocation(), shift.getStart(), shift.getEnd());
            }
        }
    }

    private void validateNoOverlappingShifts(Map<Employee, List<Shift>> shiftsByEmployee) {
        for (Map.Entry<Employee, List<Shift>> entry : shiftsByEmployee.entrySet()) {
            Employee employee = entry.getKey();
            List<Shift> shifts = entry.getValue();

            for (int i = 0; i < shifts.size(); i++) {
                for (int j = i + 1; j < shifts.size(); j++) {
                    Shift shift1 = shifts.get(i);
                    Shift shift2 = shifts.get(j);

                    if (hasTimeOverlap(shift1, shift2)) {
                        int overlapMinutes = EmployeeSchedulingConstraintProvider
                                .getMinuteOverlap(shift1, shift2);

                        throwViolation(
                                "Employee '%s' has overlapping shifts: " +
                                        "Shift %d (%s-%s at %s) and Shift %d (%s-%s at %s) " +
                                        "overlap by %d minutes",
                                employee.getName(),
                                shift1.getId(), shift1.getStart(), shift1.getEnd(), shift1.getLocation(),
                                shift2.getId(), shift2.getStart(), shift2.getEnd(), shift2.getLocation(),
                                overlapMinutes);
                    }
                }
            }
        }
    }

    private void validateOneShiftPerDay(Map<Employee, List<Shift>> shiftsByEmployee) {
        for (Map.Entry<Employee, List<Shift>> entry : shiftsByEmployee.entrySet()) {
            Employee employee = entry.getKey();
            List<Shift> shifts = entry.getValue();

            Map<LocalDate, List<Shift>> shiftsByDate = shifts.stream()
                    .collect(Collectors.groupingBy(s -> s.getStart().toLocalDate()));

            for (Map.Entry<LocalDate, List<Shift>> dateEntry : shiftsByDate.entrySet()) {
                if (dateEntry.getValue().size() > 1) {
                    throwViolation(
                            "Employee '%s' is assigned to %d shifts on %s. " +
                                    "Maximum 1 shift per day is allowed",
                            employee.getName(), dateEntry.getValue().size(), dateEntry.getKey());
                }
            }
        }
    }

    private void validateEmployeeAvailability(EmployeeSchedule schedule,
            Map<Employee, List<Shift>> shiftsByEmployee) {
        // UNAVAILABLE 날짜를 맵으로 변환
        Map<String, Map<LocalDate, AvailabilityType>> availabilityMap = new HashMap<>();
        for (Availability availability : schedule.getAvailabilityList()) {
            availabilityMap
                    .computeIfAbsent(availability.getEmployee().getId(), k -> new HashMap<>())
                    .put(availability.getDate(), availability.getAvailabilityType());
        }

        for (Map.Entry<Employee, List<Shift>> entry : shiftsByEmployee.entrySet()) {
            Employee employee = entry.getKey();
            Map<LocalDate, AvailabilityType> employeeAvailability = availabilityMap.getOrDefault(employee.getId(),
                    new HashMap<>());

            for (Shift shift : entry.getValue()) {
                LocalDate shiftDate = shift.getStart().toLocalDate();
                AvailabilityType availabilityType = employeeAvailability.get(shiftDate);

                if (availabilityType == AvailabilityType.UNAVAILABLE) {
                    throwViolation(
                            "Employee '%s' is UNAVAILABLE on %s but assigned to shift %d at %s",
                            employee.getName(), shiftDate, shift.getId(), shift.getLocation());
                }
            }
        }
    }

    private void validateSimultaneousWorkAtDifferentLocations(
            Map<Employee, List<Shift>> shiftsByEmployee) {
        for (Map.Entry<Employee, List<Shift>> entry : shiftsByEmployee.entrySet()) {
            Employee employee = entry.getKey();
            List<Shift> shifts = entry.getValue();

            // 날짜별로 그룹화
            Map<LocalDate, List<Shift>> shiftsByDate = shifts.stream()
                    .collect(Collectors.groupingBy(s -> s.getStart().toLocalDate()));

            for (Map.Entry<LocalDate, List<Shift>> dateEntry : shiftsByDate.entrySet()) {
                LocalDate date = dateEntry.getKey();
                List<Shift> dailyShifts = dateEntry.getValue();

                // 해당 날짜의 모든 Shift 쌍 비교
                for (int i = 0; i < dailyShifts.size(); i++) {
                    for (int j = i + 1; j < dailyShifts.size(); j++) {
                        Shift shift1 = dailyShifts.get(i);
                        Shift shift2 = dailyShifts.get(j);

                        // 서로 다른 location이고 시간이 중복되는지 확인
                        if (!shift1.getLocation().equals(shift2.getLocation())
                                && hasTimeOverlap(shift1, shift2)) {
                            int overlapMinutes = EmployeeSchedulingConstraintProvider
                                    .getMinuteOverlap(shift1, shift2);

                            throwViolation(
                                    "Employee '%s' is working simultaneously at different locations on %s: " +
                                            "'%s' (%s-%s) and '%s' (%s-%s) with %d minutes overlap",
                                    employee.getName(), date,
                                    shift1.getLocation(), shift1.getStart().toLocalTime(),
                                    shift1.getEnd().toLocalTime(),
                                    shift2.getLocation(), shift2.getStart().toLocalTime(),
                                    shift2.getEnd().toLocalTime(),
                                    overlapMinutes);
                        }
                    }
                }
            }
        }
    }
}
