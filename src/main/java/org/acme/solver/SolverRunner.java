package org.acme.solver;

import java.time.Duration;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.EmployeeScheduleBuilder;
import org.acme.export.ScheduleExportCoordinator;
import org.acme.model.EmployeeSchedule;
import org.acme.solver.algorithm.EmployeeSchedulingConstraintProvider;
import org.acme.solver.output.SchedulePrinter;
import org.acme.solver.validation.SolutionValidator;
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

    @Inject
    org.acme.util.SolutionClonerUtil solutionClonerUtil;

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

    // Incremental Solver Configuration
    @ConfigProperty(name = "solver.incremental.enabled", defaultValue = "false")
    boolean incrementalEnabled;

    @ConfigProperty(name = "solver.incremental.iteration-seconds", defaultValue = "30")
    long iterationSeconds;

    @ConfigProperty(name = "solver.incremental.max-total-minutes", defaultValue = "10")
    long maxTotalMinutes;

    @ConfigProperty(name = "solver.incremental.min-iterations", defaultValue = "2")
    int minIterations;

    private final SolutionValidator solutionValidator = new SolutionValidator();
    private final ScheduleExportCoordinator scheduleExportCoordinator = new ScheduleExportCoordinator();

    public void run(String jsonInput) {
        runWithResult(jsonInput);
    }

    /**
     * Solver를 실행하고 결과를 반환합니다.
     */
    public EmployeeSchedule runWithResult(String jsonInput) {
        LOG.info("--- Solver calculation started ---");

        try {
            // 1. Parse JSON
            PlanningRequest request = objectMapper.readValue(jsonInput, PlanningRequest.class);
            LOG.info("Organization: {}", request.organization().name());

            // 2. Solve (Legacy or One-shot)
            // Note: Cloud Run Job uses WorkerResource which calls solveIncremental
            // manually.
            // This method is primarily for local testing or simple runs.
            EmployeeSchedule solution = solve(request);

            // 3. Output
            LOG.info("Score: {}", solution.getScore());
            solutionValidator.validate(solution, LOG);

            if (exportEnabled && SchedulePrinter.isLocalLaunchMode()) {
                try {
                    String outputPath = scheduleExportCoordinator.exportToMarkdown(solution, exportOutputDir);
                    LOG.info("Schedule exported to: {}", outputPath);
                } catch (Exception e) {
                    LOG.warn("Failed to export schedule to markdown: {}", e.getMessage(), e);
                }
            }

            LOG.info("--- Solver calculation ended ---");
            return solution;

        } catch (Exception e) {
            LOG.error("Solving failed", e);
            throw new RuntimeException(e);
        }
    }

    public EmployeeSchedule solve(PlanningRequest request) {
        // 1. Convert to Domain Model
        EmployeeSchedule problem = employeeScheduleBuilder.build(request);
        // 2. Solve with default termination
        return createSolver(Duration.ofSeconds(spentLimit)).solve(problem);
    }

    /**
     * 점진적(Incremental) 솔버 실행
     */
    public EmployeeSchedule solveIncremental(PlanningRequest request, String executionId,
            java.util.function.Consumer<EmployeeSchedule> intermediateCallback) {

        if (!incrementalEnabled) {
            return solve(request);
        }

        EmployeeSchedule problem = employeeScheduleBuilder.build(request);
        EmployeeSchedule bestSolution = null;
        org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore previousScore = null;

        long startTime = System.currentTimeMillis();
        int iteration = 0;

        LOG.info("Starting incremental solver: executionId={}, iterationSeconds={}, maxTotalMinutes={}",
                executionId, iterationSeconds, maxTotalMinutes);

        while (true) {
            iteration++;
            LOG.info("Iteration {} started", iteration);

            // Solver 생성
            Solver<EmployeeSchedule> solver = createSolver(Duration.ofSeconds(iterationSeconds));

            // Warm start: 이전 해가 있으면 초기해로 설정
            if (bestSolution != null) {
                problem = solutionClonerUtil.cloneSolution(bestSolution);
            }

            // 실행
            EmployeeSchedule currentSolution = solver.solve(problem);
            org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore currentScore = currentSolution.getScore();

            LOG.info("Iteration {} check: currentScore={}, previousScore={}", iteration, currentScore, previousScore);

            // 더 나은 해이거나 첫 실행이면 bestSolution 업데이트
            if (bestSolution == null || currentScore.compareTo(bestSolution.getScore()) >= 0) {
                bestSolution = currentSolution;

                try {
                    intermediateCallback.accept(bestSolution);
                } catch (Exception e) {
                    LOG.warn("Intermediate callback failed but solver will continue", e);
                }
            }

            // 종료 조건 1: 수렴
            if (iteration >= minIterations && currentScore.equals(previousScore)) {
                LOG.info("Converged after {} iterations. Score: {}", iteration, currentScore);
                break;
            }

            // 종료 조건 2: 최대 시간 초과
            long elapsedMinutes = (System.currentTimeMillis() - startTime) / 1000 / 60;
            if (elapsedMinutes >= maxTotalMinutes) {
                LOG.info("Max time limit reached after {} iterations", iteration);
                break;
            }

            previousScore = currentScore;
        }

        return bestSolution;
    }

    private Solver<EmployeeSchedule> createSolver(Duration termination) {
        SolverFactory<EmployeeSchedule> solverFactory = SolverFactory.create(new SolverConfig()
                .withSolutionClass(EmployeeSchedule.class)
                .withEntityClasses(org.acme.model.Shift.class)
                .withConstraintProviderClass(EmployeeSchedulingConstraintProvider.class)
                .withTerminationSpentLimit(termination)
                .withMoveThreadCount(moveThreadCount)
                .withEnvironmentMode(environmentMode)
                .withRandomSeed(randomSeed));

        return solverFactory.buildSolver();
    }
}
