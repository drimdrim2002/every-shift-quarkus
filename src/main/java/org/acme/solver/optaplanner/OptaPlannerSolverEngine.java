package org.acme.solver.optaplanner;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.acme.solver.adapter.EmployeeScheduleProjection;
import org.acme.solver.algorithm.EmployeeSchedulingConstraintProvider;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveListener;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.core.SolverEngine;
import org.acme.solver.core.TerminationReason;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.config.solver.EnvironmentMode;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;

/**
 * 기존 OptaPlanner 실행을 {@link SolverEngine} 뒤에 격리하는 adapter입니다.
 */
@ApplicationScoped
@Typed(OptaPlannerSolverEngine.class)
public class OptaPlannerSolverEngine implements SolverEngine {

    private static final Logger LOG = LoggerFactory.getLogger(OptaPlannerSolverEngine.class);
    private static final long CANCELLATION_POLL_MILLIS = 25L;

    @Inject
    EmployeeScheduleProjection projection;

    @ConfigProperty(name = "solver.move-thread-count", defaultValue = "AUTO")
    String moveThreadCount;

    @ConfigProperty(name = "solver.environment-mode", defaultValue = "REPRODUCIBLE")
    String environmentMode;

    @Override
    public SolveResult<RosterSolution> solve(
            PlanningProblem problem,
            SolveOptions options,
            SolveListener listener) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(listener, "listener");

        long startedNanos = System.nanoTime();
        if (options.cancellationToken().isCancellationRequested()) {
            return emptyResult(TerminationReason.CANCELLED, options.randomSeed(), startedNanos);
        }
        if (options.isDeadlineReached()) {
            return emptyResult(TerminationReason.DEADLINE_REACHED, options.randomSeed(), startedNanos);
        }

        EmployeeSchedule input = options.warmStart()
                .map(solution -> projection.toEmployeeSchedule(problem, solution))
                .orElseGet(() -> projection.toEmployeeSchedule(problem));
        Solver<EmployeeSchedule> solver = createSolver(options);

        TerminationReason externallyRequestedReason = null;
        EmployeeSchedule solvedSchedule;
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "optaplanner-solver-engine");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<EmployeeSchedule> future = executor.submit(() -> solver.solve(input));
            while (true) {
                if (options.cancellationToken().isCancellationRequested()) {
                    externallyRequestedReason = TerminationReason.CANCELLED;
                    solver.terminateEarly();
                } else if (options.isDeadlineReached()) {
                    externallyRequestedReason = TerminationReason.DEADLINE_REACHED;
                    solver.terminateEarly();
                }

                try {
                    solvedSchedule = future.get(CANCELLATION_POLL_MILLIS, TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException ignored) {
                    // cancellation/deadline을 단조 시간으로 주기적으로 확인합니다.
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    externallyRequestedReason = TerminationReason.CANCELLED;
                    solver.terminateEarly();
                    throw new IllegalStateException("OptaPlanner solve 대기 중 interrupt되었습니다.", e);
                } catch (ExecutionException e) {
                    throw propagate(e.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        TerminationReason reason = externallyRequestedReason != null
                ? externallyRequestedReason
                : inferConfiguredTermination(options);
        RosterSolution solution;
        try {
            solution = projection.toRosterSolution(problem, solvedSchedule);
        } catch (IllegalArgumentException incompleteSolution) {
            if (reason != TerminationReason.CANCELLED && reason != TerminationReason.DEADLINE_REACHED) {
                throw incompleteSolution;
            }
            LOG.info("OptaPlanner가 complete solution 생성 전에 종료되었습니다: {}", reason);
            return emptyResult(reason, options.randomSeed(), startedNanos);
        }

        notifyListenerSafely(listener, solution);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        return new SolveResult<>(
                solution,
                solution.score(),
                reason,
                1L,
                0L,
                elapsedMillis,
                options.randomSeed());
    }

    private Solver<EmployeeSchedule> createSolver(SolveOptions options) {
        TerminationConfig termination = new TerminationConfig();
        effectiveSpentLimit(options).ifPresent(termination::setSpentLimit);
        if (options.maxEvaluations() != SolveOptions.UNLIMITED) {
            termination.setScoreCalculationCountLimit(options.maxEvaluations());
        }
        if (options.maxIterations() != SolveOptions.UNLIMITED) {
            termination.setStepCountLimit(toIntLimit(options.maxIterations(), "maxIterations"));
        }
        if (options.maxStagnantEvaluations() != SolveOptions.UNLIMITED) {
            termination.setUnimprovedStepCountLimit(
                    toIntLimit(options.maxStagnantEvaluations(), "maxStagnantEvaluations"));
        }
        if (!termination.isConfigured()) {
            throw new IllegalArgumentException("OptaPlanner 실행에는 하나 이상의 종료 옵션이 필요합니다.");
        }

        SolverConfig config = new SolverConfig()
                .withSolutionClass(EmployeeSchedule.class)
                .withEntityClasses(Shift.class)
                .withConstraintProviderClass(EmployeeSchedulingConstraintProvider.class)
                .withTerminationConfig(termination)
                .withMoveThreadCount(moveThreadCount)
                .withEnvironmentMode(EnvironmentMode.valueOf(environmentMode.trim().toUpperCase()))
                .withRandomSeed(options.randomSeed());
        return SolverFactory.<EmployeeSchedule>create(config).buildSolver();
    }

    private java.util.Optional<Duration> effectiveSpentLimit(SolveOptions options) {
        Duration effective = options.spentLimit().orElse(null);
        if (options.hasDeadline()) {
            Duration remaining = options.remainingUntilDeadline();
            if (remaining.isZero()) {
                remaining = Duration.ofNanos(1L);
            }
            if (effective == null || remaining.compareTo(effective) < 0) {
                effective = remaining;
            }
        }
        return java.util.Optional.ofNullable(effective);
    }

    private static int toIntLimit(long value, String name) {
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + "는 int 범위를 넘을 수 없습니다: " + value);
        }
        return (int) value;
    }

    private static TerminationReason inferConfiguredTermination(SolveOptions options) {
        if (options.maxEvaluations() != SolveOptions.UNLIMITED
                && options.spentLimit().isEmpty()
                && !options.hasDeadline()) {
            return TerminationReason.MAX_EVALUATIONS_REACHED;
        }
        if (options.maxIterations() != SolveOptions.UNLIMITED
                && options.spentLimit().isEmpty()
                && !options.hasDeadline()) {
            return TerminationReason.MAX_ITERATIONS_REACHED;
        }
        return TerminationReason.COMPLETED;
    }

    private static void notifyListenerSafely(SolveListener listener, RosterSolution solution) {
        try {
            listener.onBestSolution(solution);
        } catch (RuntimeException listenerFailure) {
            LOG.warn("SolveListener failed but solver result remains valid", listenerFailure);
        }
    }

    private static SolveResult<RosterSolution> emptyResult(
            TerminationReason reason,
            long seed,
            long startedNanos) {
        return new SolveResult<>(
                null,
                null,
                reason,
                0L,
                0L,
                Duration.ofNanos(System.nanoTime() - startedNanos).toMillis(),
                seed);
    }

    private static RuntimeException propagate(Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException("OptaPlanner solve가 실패했습니다.", cause);
    }
}
