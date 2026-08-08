package org.acme.solver;

import java.time.Duration;
import java.util.Locale;
import java.util.function.Consumer;

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

    static final String SEED_MODE_BASE = "BASE";
    static final String SEED_MODE_BASE_PLUS_PASS = "BASE_PLUS_PASS";

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

    // Incremental multi-pass configuration
    @ConfigProperty(name = "solver.incremental.enabled", defaultValue = "false")
    boolean incrementalEnabled;

    @ConfigProperty(name = "solver.incremental.first-iteration-seconds", defaultValue = "60")
    long firstIterationSeconds;

    @ConfigProperty(name = "solver.incremental.iteration-seconds", defaultValue = "60")
    long iterationSeconds;

    @ConfigProperty(name = "solver.incremental.max-total-minutes", defaultValue = "10")
    long maxTotalMinutes;

    /**
     * 레거시 키. 새 multi-pass 정책에서는 강제 최소 회수를 적용하지 않으며 기본값은 1.
     * 로직 no-op (호환용 유지).
     */
    @ConfigProperty(name = "solver.incremental.min-iterations", defaultValue = "1")
    int minIterations;

    @ConfigProperty(name = "solver.incremental.max-iterations", defaultValue = "6")
    int maxIterations;

    @ConfigProperty(name = "solver.incremental.save-on-pass-end", defaultValue = "true")
    boolean saveOnPassEnd;

    @ConfigProperty(name = "solver.incremental.save-on-inner-best", defaultValue = "false")
    boolean saveOnInnerBest;

    @ConfigProperty(name = "solver.incremental.seed-mode", defaultValue = "BASE_PLUS_PASS")
    String seedMode;

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
            // incremental.enabled 플래그를 따르면 로컬 JOB 파일 실행도 multi-pass 가능.
            EmployeeSchedule solution = incrementalEnabled
                    ? solveIncremental(request, "local", ignored -> {
                    })
                    : solve(request);

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
     * 점진적(Incremental) multi-pass 솔버 실행.
     *
     * <p>패스 종료 시 {@code onPassEnd} 로 전역 best 를 전달한다.
     * 엔진 내부 best 리스너 저장은 {@code solver.incremental.save-on-inner-best} 로 제어한다.</p>
     */
    public EmployeeSchedule solveIncremental(PlanningRequest request, String executionId,
            Consumer<EmployeeSchedule> onPassEnd) {
        return solveIncremental(request, executionId, onPassEnd, null);
    }

    /**
     * 점진적 multi-pass 솔버. pass-end / inner-best 콜백을 분리한다.
     *
     * @param onPassEnd   패스 종료 시 전역 best (save-on-pass-end=true 일 때)
     * @param onInnerBest 엔진 내부 best (save-on-inner-best=true 일 때; null 이면 no-op)
     */
    public EmployeeSchedule solveIncremental(PlanningRequest request, String executionId,
            Consumer<EmployeeSchedule> onPassEnd,
            Consumer<EmployeeSchedule> onInnerBest) {

        if (!incrementalEnabled) {
            return solve(request);
        }

        EmployeeSchedule source = employeeScheduleBuilder.build(request);
        PlanningProblem problem = planningProblemMapper.toPlanningProblem(source);
        RosterSolution bestSolution = null;

        long startNanos = System.nanoTime();
        long deadlineNanos = saturatedAdd(startNanos, Duration.ofMinutes(maxTotalMinutes).toNanos());
        int pass = 0;

        LOG.info(
                "Starting incremental multi-pass solver: executionId={}, firstIterationSeconds={}, iterationSeconds={}, maxTotalMinutes={}, maxIterations={}, saveOnPassEnd={}, saveOnInnerBest={}, seedMode={}",
                executionId, firstIterationSeconds, iterationSeconds, maxTotalMinutes, maxIterations,
                saveOnPassEnd, saveOnInnerBest, seedMode);

        while (true) {
            pass++;
            long currentPassSeconds = (pass == 1) ? firstIterationSeconds : iterationSeconds;
            RosterSolution bestBefore = bestSolution;
            RosterScore bestBeforeScore = bestBefore != null ? bestBefore.score() : null;
            long passSeed = resolvePassSeed(pass);

            LOG.info("Pass {} started: spentLimit={}s, seed={}, bestBefore={}",
                    pass, currentPassSeconds, passSeed, bestBeforeScore);

            SolveOptions.Builder optionsBuilder = SolveOptions.builder()
                    .spentLimit(Duration.ofSeconds(currentPassSeconds))
                    .deadlineNanos(deadlineNanos)
                    .randomSeed(passSeed);
            if (bestSolution != null) {
                optionsBuilder.warmStart(bestSolution);
            }

            SolveListener listener = buildInnerBestListener(problem, onInnerBest);
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
                LOG.info("Pass {} ended without a new complete solution: {} (keeping global best={})",
                        pass, solveResult.terminationReason(), bestSolution.score());
                break;
            }

            // 엄격 개선 또는 최초 해만 전역 best 갱신
            if (bestSolution == null || currentSolution.score().compareTo(bestSolution.score()) > 0) {
                bestSolution = currentSolution;
            }

            RosterScore bestAfterScore = bestSolution.score();
            boolean improved = bestBeforeScore != null && bestAfterScore.compareTo(bestBeforeScore) > 0;
            boolean hardViolated = bestAfterScore.hardScore() < 0;

            if (saveOnPassEnd && onPassEnd != null) {
                onPassEnd.accept(employeeScheduleProjection.toEmployeeSchedule(problem, bestSolution));
            }

            TerminationReason terminationReason = determineTerminationReason(
                    pass,
                    maxIterations,
                    bestAfterScore,
                    bestBeforeScore,
                    System.nanoTime(),
                    deadlineNanos);

            LOG.info(
                    "Pass {} ended: bestBefore={}, bestAfter={}, hard={}, improved={}, hardViolated={}, reason={}",
                    pass, bestBeforeScore, bestAfterScore, bestAfterScore.hardScore(),
                    improved, hardViolated, terminationReason);

            if (terminationReason != TerminationReason.CONTINUE) {
                if (terminationReason == TerminationReason.CONVERGED) {
                    LOG.info("Converged after {} passes. Score: {}", pass, bestAfterScore);
                } else if (terminationReason == TerminationReason.DEADLINE_REACHED) {
                    long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
                    LOG.info("Max time limit reached after {} passes (elapsed={}ms)", pass, elapsedMs);
                } else if (terminationReason == TerminationReason.MAX_ITERATIONS_REACHED) {
                    LOG.info("Max pass limit reached after {} passes. Score: {}", pass, bestAfterScore);
                } else {
                    LOG.info("Incremental solver stopped after {} passes: {}", pass, terminationReason);
                }
                break;
            }
        }

        return employeeScheduleProjection.toEmployeeSchedule(problem, bestSolution);
    }

    /**
     * multi-pass 종료 정책.
     *
     * <ol>
     *   <li>deadline 도달 → DEADLINE_REACHED</li>
     *   <li>pass &gt;= maxIterations → MAX_ITERATIONS_REACHED</li>
     *   <li>전역 best hard &lt; 0 → CONTINUE</li>
     *   <li>1회차(bestBefore null) hard≥0 → CONVERGED</li>
     *   <li>전역 best 엄격 개선 → CONTINUE</li>
     *   <li>그 외(비개선 ∧ hard≥0) → CONVERGED</li>
     * </ol>
     *
     * <p>{@code minIterations} 는 레거시 no-op.</p>
     */
    TerminationReason determineTerminationReason(
            int pass,
            int maxIterations,
            RosterScore bestAfterPass,
            RosterScore bestBeforePass,
            long nowNanos,
            long deadlineNanos) {
        if (nowNanos - deadlineNanos >= 0L) {
            return TerminationReason.DEADLINE_REACHED;
        }

        if (maxIterations > 0 && pass >= maxIterations) {
            return TerminationReason.MAX_ITERATIONS_REACHED;
        }

        if (bestAfterPass == null) {
            return TerminationReason.CONVERGED;
        }

        if (bestAfterPass.hardScore() < 0) {
            return TerminationReason.CONTINUE;
        }

        // 1회차: hard≥0 이면 즉시 종료 (강제 최소 2회 없음)
        if (bestBeforePass == null) {
            return TerminationReason.CONVERGED;
        }

        // 2회차 이후: 엄격 개선이면 계속
        if (bestAfterPass.compareTo(bestBeforePass) > 0) {
            return TerminationReason.CONTINUE;
        }

        // 비개선 ∧ hard≥0
        return TerminationReason.CONVERGED;
    }

    /**
     * 레거시 시그니처 호환 (테스트/외부 호출). maxIterations 는 인스턴스 필드 사용.
     */
    TerminationReason determineTerminationReason(int iteration,
            RosterScore currentScore,
            RosterScore previousScore,
            long nowNanos,
            long deadlineNanos) {
        return determineTerminationReason(
                iteration,
                this.maxIterations,
                currentScore,
                previousScore,
                nowNanos,
                deadlineNanos);
    }

    long resolvePassSeed(int pass) {
        String mode = seedMode == null ? SEED_MODE_BASE_PLUS_PASS : seedMode.trim().toUpperCase(Locale.ROOT);
        if (SEED_MODE_BASE.equals(mode)) {
            return randomSeed;
        }
        // 기본: BASE_PLUS_PASS
        return randomSeed + (long) pass;
    }

    private SolveListener buildInnerBestListener(PlanningProblem problem, Consumer<EmployeeSchedule> onInnerBest) {
        if (!saveOnInnerBest) {
            return SolveListener.noop();
        }
        Consumer<EmployeeSchedule> callback = onInnerBest;
        if (callback == null) {
            return SolveListener.noop();
        }
        return solution -> callback.accept(
                employeeScheduleProjection.toEmployeeSchedule(problem, solution));
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
