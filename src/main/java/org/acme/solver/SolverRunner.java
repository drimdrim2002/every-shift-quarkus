package org.acme.solver;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.api.dto.PlanningRequest;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.acme.solver.algorithm.EmployeeSchedulingConstraintProvider;
import org.acme.util.DtoConverter;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.config.solver.EnvironmentMode;
import org.optaplanner.core.config.solver.SolverConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
            LOG.info("Score: {}", score);
            printSchedule(solution);

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
}
