package org.acme.solver;

import java.time.Duration;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.EmployeeScheduleBuilder;
import org.acme.export.ScheduleExportCoordinator;
import org.acme.model.EmployeeSchedule;
import org.acme.solver.adapter.EmployeeScheduleProjection;
import org.acme.solver.adapter.PlanningProblemMapper;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveListener;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.core.SolverEngine;
import org.acme.solver.core.TerminationReason;
import org.acme.solver.output.SchedulePrinter;
import org.acme.solver.validation.SolutionValidator;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
    PlanningProblemMapper planningProblemMapper;

    @Inject
    EmployeeScheduleProjection employeeScheduleProjection;

    @Inject
    SolverEngine solverEngine;

    @Inject
    ScheduleExportCoordinator scheduleExportCoordinator;

    @ConfigProperty(name = "solver.termination.spent-limit", defaultValue = "10")
    long spentLimit;

    @ConfigProperty(name = "solver.random-seed", defaultValue = "0")
    long randomSeed;

    @ConfigProperty(name = "app.export.output-dir", defaultValue = "/tmp/schedule-output")
    String exportOutputDir;

    @ConfigProperty(name = "app.export.enabled", defaultValue = "true")
    boolean exportEnabled;

    // Incremental Solver Configuration
    @ConfigProperty(name = "solver.incremental.enabled", defaultValue = "false")
    boolean incrementalEnabled;

    @ConfigProperty(name = "solver.incremental.first-iteration-seconds", defaultValue = "60")
    long firstIterationSeconds;

    @ConfigProperty(name = "solver.incremental.iteration-seconds", defaultValue = "30")
    long iterationSeconds;

    @ConfigProperty(name = "solver.incremental.max-total-minutes", defaultValue = "10")
    long maxTotalMinutes;

    @ConfigProperty(name = "solver.incremental.min-iterations", defaultValue = "2")
    int minIterations;

    @ConfigProperty(name = "solver.incremental.max-iterations", defaultValue = "30")
    int maxIterations;

    private final SolutionValidator solutionValidator = new SolutionValidator();

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
                    String outputPath = scheduleExportCoordinator.exportToJson(solution, exportOutputDir);
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
        EmployeeSchedule source = employeeScheduleBuilder.build(request);
        PlanningProblem problem = planningProblemMapper.toPlanningProblem(source);
        SolveOptions options = SolveOptions.builder()
                .spentLimit(Duration.ofSeconds(spentLimit))
                .randomSeed(randomSeed)
                .build();
        SolveResult<RosterSolution> result = solverEngine.solve(problem, options, SolveListener.noop());
        RosterSolution solution = requireSolution(result);
        return employeeScheduleProjection.toEmployeeSchedule(problem, solution);
    }

    /**
     * 점진적(Incremental) 솔버 실행
     */
    public EmployeeSchedule solveIncremental(PlanningRequest request, String executionId,
            java.util.function.Consumer<EmployeeSchedule> intermediateCallback) {

        if (!incrementalEnabled) {
            return solve(request);
        }

        EmployeeSchedule source = employeeScheduleBuilder.build(request);
        PlanningProblem problem = planningProblemMapper.toPlanningProblem(source);
        RosterSolution bestSolution = null;

        long startNanos = System.nanoTime();
        long deadlineNanos = saturatedAdd(startNanos, Duration.ofMinutes(maxTotalMinutes).toNanos());
        int iteration = 0;

        LOG.info("Starting incremental solver: executionId={}, firstIterationSeconds={}, iterationSeconds={}, maxTotalMinutes={}, maxIterations={}",
                executionId, firstIterationSeconds, iterationSeconds, maxTotalMinutes, maxIterations);

        while (true) {
            iteration++;
            LOG.info("Iteration {} started", iteration);

            long currentIterationSeconds = (iteration == 1) ? firstIterationSeconds : iterationSeconds;

            // 루프 시작 시점의 이전 최고 점수 기록
            RosterScore previousBestScore = (bestSolution != null) ? bestSolution.score() : null;

            SolveOptions.Builder optionsBuilder = SolveOptions.builder()
                    .spentLimit(Duration.ofSeconds(currentIterationSeconds))
                    .deadlineNanos(deadlineNanos)
                    .randomSeed(randomSeed);
            if (bestSolution != null) {
                optionsBuilder.warmStart(bestSolution);
            }

            SolveListener listener = solution -> intermediateCallback.accept(
                    employeeScheduleProjection.toEmployeeSchedule(problem, solution));
            SolveResult<RosterSolution> solveResult = solverEngine.solve(
                    problem,
                    optionsBuilder.build(),
                    listener);
            RosterSolution currentSolution = solveResult.bestSolution();
            if (currentSolution == null) {
                if (bestSolution == null) {
                    throw new IllegalStateException("complete solution 없이 solver가 종료되었습니다: "
                            + solveResult.terminationReason());
                }
                LOG.info("Iteration {} ended without a new complete solution: {}",
                        iteration, solveResult.terminationReason());
                break;
            }
            RosterScore currentScore = currentSolution.score();

            // 더 나은 해이거나 첫 실행이면 bestSolution 업데이트
            if (bestSolution == null || currentScore.compareTo(bestSolution.score()) >= 0) {
                bestSolution = currentSolution;
            }

            RosterScore currentBestScore = bestSolution.score();
            LOG.info("Iteration {} check: currentBestScore={}, previousBestScore={}", iteration, currentBestScore, previousBestScore);

            TerminationReason terminationReason = determineTerminationReason(
                    iteration,
                    currentBestScore,
                    previousBestScore,
                    System.nanoTime(),
                    deadlineNanos);

            if (terminationReason != TerminationReason.CONTINUE) {
                if (terminationReason == TerminationReason.CONVERGED) {
                    LOG.info("Converged after {} iterations. Score: {}", iteration, currentBestScore);
                } else if (terminationReason == TerminationReason.DEADLINE_REACHED) {
                    long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
                    LOG.info("Max time limit reached after {} iterations (elapsed={}ms)", iteration, elapsedMs);
                } else {
                    LOG.info("Max iteration limit reached after {} iterations", iteration);
                }
                break;
            }
        }

        return employeeScheduleProjection.toEmployeeSchedule(problem, bestSolution);
    }

    TerminationReason determineTerminationReason(int iteration,
            RosterScore currentScore,
            RosterScore previousScore,
            long nowNanos,
            long deadlineNanos) {
        if (iteration >= minIterations && currentScore.equals(previousScore)) {
            return TerminationReason.CONVERGED;
        }

        if (maxIterations > 0 && iteration >= maxIterations) {
            return TerminationReason.MAX_ITERATIONS_REACHED;
        }

        if (nowNanos - deadlineNanos >= 0L) {
            return TerminationReason.DEADLINE_REACHED;
        }

        return TerminationReason.CONTINUE;
    }

    private static RosterSolution requireSolution(SolveResult<RosterSolution> result) {
        if (result.bestSolution() == null) {
            throw new IllegalStateException("complete solution 없이 solver가 종료되었습니다: "
                    + result.terminationReason());
        }
        return result.bestSolution();
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

}
