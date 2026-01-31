package org.acme.solver;

import java.time.Duration;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.EmployeeScheduleBuilder;
import org.acme.model.EmployeeSchedule;
import org.acme.solver.algorithm.EmployeeSchedulingConstraintProvider;
import org.acme.solver.output.SchedulePrinter;
import org.acme.solver.validation.SolutionValidator;
import org.acme.util.ScheduleExporter;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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

    @Inject
    EmployeeScheduleBuilder employeeScheduleBuilder;

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

    private final SolutionValidator solutionValidator = new SolutionValidator();

    public void run(String jsonInput) {
        LOG.info("--- Solver calculation started ---");

        try {
            // 1. Parse JSON
            PlanningRequest request = objectMapper.readValue(jsonInput, PlanningRequest.class);
            LOG.info("Organization: {}", request.organization().name());

            // 2. Solve
            EmployeeSchedule solution = solve(request);

            // 3. Output
            LOG.info("Score: {}", solution.getScore());
            solutionValidator.validate(solution, LOG);
            SchedulePrinter.printSchedule(solution, LOG);

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
        EmployeeSchedule problem = employeeScheduleBuilder.build(request);

        // 2. Build Solver
        SolverFactory<EmployeeSchedule> solverFactory = SolverFactory.create(new SolverConfig()
                .withSolutionClass(EmployeeSchedule.class)
                .withEntityClasses(org.acme.model.Shift.class)
                .withConstraintProviderClass(EmployeeSchedulingConstraintProvider.class)
                .withTerminationSpentLimit(Duration.ofSeconds(spentLimit))
                .withMoveThreadCount(moveThreadCount)
                .withEnvironmentMode(environmentMode)
                .withRandomSeed(randomSeed));

        Solver<EmployeeSchedule> solver = solverFactory.buildSolver();

        // 3. Solve
        return solver.solve(problem);
    }
}
