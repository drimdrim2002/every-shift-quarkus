package org.acme.solver.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.EmployeeScheduleBuilder;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.acme.solver.adapter.PlanningProblemMapper;
import org.acme.solver.algorithm.EmployeeSchedulingConstraintProvider;
import org.acme.solver.alns.AdaptiveOperatorConfig;
import org.acme.solver.alns.AlnsIterationConfig;
import org.acme.solver.alns.AlnsRunMetrics;
import org.acme.solver.alns.AlnsSolverConfig;
import org.acme.solver.alns.AlnsSolverEngine;
import org.acme.solver.alns.FairnessAwareRegret2Repair;
import org.acme.solver.alns.FairnessHotspotRemoval;
import org.acme.solver.alns.GreedyRepair;
import org.acme.solver.alns.OperatorCompatibilityMatrix;
import org.acme.solver.alns.OperatorStatistics;
import org.acme.solver.alns.PreceptorRelationGroupRemoval;
import org.acme.solver.alns.RandomRemoval;
import org.acme.solver.alns.Regret2Repair;
import org.acme.solver.alns.RelatedShiftRemoval;
import org.acme.solver.alns.RelationAwareRepair;
import org.acme.solver.alns.SaAcceptanceConfig;
import org.acme.solver.benchmark.Phase6BenchmarkStatistics.ScorePair;
import org.acme.solver.benchmark.Phase6BenchmarkStatistics.WinTieLoss;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.initial.InitialSolutionBuilder;
import org.acme.solver.lahc.OptaStyleLocalSearchEngine;
import org.acme.solver.lahc.OptaStyleLocalSearchMetrics;
import org.acme.solver.lahc.FairnessRestrictedLocalSearchEngine;
import org.acme.solver.lahc.FairnessRestrictedLocalSearchMetrics;
import org.acme.solver.lahc.SequentialHybridMetrics;
import org.acme.solver.lahc.SequentialHybridSolverEngine;
import org.acme.solver.optaplanner.OptaPlannerScoreAdapter;
import org.acme.solver.score.FullScoreCalculator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.score.buildin.bendable.BendableScore;
import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.config.solver.EnvironmentMode;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;
import org.optaplanner.core.impl.solver.DefaultSolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * OptaPlanner와 선택된 POJO 후보를 동일 입력/seed로 교차 실행하는 Phase 6 gate benchmark입니다.
 * benchmark 실행은 기본 test lifecycle에서 제외되며 shell script가 명시적으로 활성화합니다.
 */
@Tag("benchmark")
class Phase6PairedBenchmarkTest {

    private static final int SCHEMA_VERSION = 1;
    private static final List<String> DEFAULT_DATASETS = List.of(
            "fairness.json", "preceptor.json", "request.json", "sample.json");
    private static final List<Long> DEFAULT_SEEDS = List.of(
            42L, 43L, 44L, 45L, 46L, 47L, 48L, 49L, 50L, 51L);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final EmployeeScheduleBuilder scheduleBuilder = new EmployeeScheduleBuilder();
    private final PlanningProblemMapper problemMapper = new PlanningProblemMapper();

    @Test
    void capturePairedBenchmark() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("phase6.benchmark.enabled"),
                "Phase 6 benchmark는 scripts/benchmark/run-phase6-benchmark.sh로만 실행합니다.");

        BenchmarkConfig config = BenchmarkConfig.fromSystemProperties();
        prepareOutput(config.output());
        append(config.output(), metadataRecord(config));

        if (config.warmup()) {
            for (Profile profile : config.profiles()) {
                DatasetInput warmup = loadDataset("fairness_test.json");
                runSafely(Engine.OPTAPLANNER, warmup, config.seeds().getFirst(), 0,
                        profile, 0, config, true);
                runSafely(config.candidateEngine(), warmup, config.seeds().getFirst(), 0,
                        profile, 1, config, true);
            }
        }

        List<PairRun> pairs = new ArrayList<>();
        int runOrder = 0;
        for (int datasetIndex = 0; datasetIndex < config.datasets().size(); datasetIndex++) {
            String dataset = config.datasets().get(datasetIndex);
            DatasetInput input = loadDataset(dataset);
            for (int seedIndex = 0; seedIndex < config.seeds().size(); seedIndex++) {
                long seed = config.seeds().get(seedIndex);
                for (int repeat = 1; repeat <= config.repeats(); repeat++) {
                    for (int profileIndex = 0; profileIndex < config.profiles().size(); profileIndex++) {
                        Profile profile = config.profiles().get(profileIndex);
                        List<Engine> order = crossedEngineOrder(
                                datasetIndex + seedIndex + repeat + profileIndex, config.candidateEngine());
                        Map<Engine, RunResult> byEngine = new EnumMap<>(Engine.class);
                        for (Engine engine : order) {
                            RunResult result = runSafely(
                                    engine, input, seed, repeat, profile, runOrder++, config, false);
                            byEngine.put(engine, result);
                            append(config.output(), result.toJson(objectMapper, config));
                        }
                        PairRun pair = new PairRun(
                                dataset,
                                seed,
                                repeat,
                                profile,
                                order.getFirst(),
                                byEngine.get(Engine.OPTAPLANNER),
                                byEngine.get(config.candidateEngine()));
                        pairs.add(pair);
                        append(config.output(), pair.toJson(objectMapper, config));
                    }
                }
            }
        }

        BenchmarkReport report = BenchmarkReport.from(config, pairs);
        report.writeJson(objectMapper, config.summaryOutput());
        report.writeMarkdown(config.reportOutput());

        int expectedPairs = config.datasets().size() * config.seeds().size()
                * config.repeats() * config.profiles().size();
        assertEquals(expectedPairs, pairs.size());
        assertEquals(0L, pairs.stream()
                .flatMap(pair -> pair.runs().stream())
                .filter(RunResult::executionFailed)
                .count(), "실행 예외가 발생했습니다. 생성된 report와 raw JSONL을 확인하세요.");
    }

    private RunResult runSafely(
            Engine engine,
            DatasetInput input,
            long seed,
            int repeat,
            Profile profile,
            int runOrder,
            BenchmarkConfig config,
            boolean warmup) {
        long startedNanos = System.nanoTime();
        try {
            return switch (engine) {
                case OPTAPLANNER -> runOptaPlanner(
                        input, seed, repeat, profile, runOrder, config, warmup);
                case POJO_ALNS_BASELINE, POJO_ALNS_FAIRNESS_HOTSPOT -> runPojoAlns(
                        engine, input, seed, repeat, profile, runOrder, config, warmup);
                case POJO_OPTA_STYLE_CHANGE_SWAP -> runOptaStyleLocalMove(
                        input, seed, repeat, profile, runOrder, config, warmup);
                case POJO_HYBRID_OPTA_THEN_ALNS, POJO_HYBRID_ALNS_THEN_OPTA -> runSequentialHybrid(
                        engine, input, seed, repeat, profile, runOrder, config, warmup);
                case POJO_FAIRNESS_RESTRICTED_CHANGE_SWAP,
                        POJO_FAIRNESS_HOTSPOT_PROTECTED_REASSIGN -> runFairnessRestrictedLocalMove(
                                engine, input, seed, repeat, profile, runOrder, config, warmup);
            };
        } catch (RuntimeException failure) {
            return RunResult.failure(
                    engine,
                    input.dataset(),
                    input.sha256(),
                    seed,
                    repeat,
                    profile,
                    runOrder,
                    Duration.ofNanos(System.nanoTime() - startedNanos).toMillis(),
                    failure.getClass().getSimpleName());
        }
    }

    private RunResult runOptaPlanner(
            DatasetInput input,
            long seed,
            int repeat,
            Profile profile,
            int runOrder,
            BenchmarkConfig config,
            boolean warmup) {
        EmployeeSchedule problem = scheduleBuilder.build(input.request());
        Solver<EmployeeSchedule> solver = createOptaPlannerSolver(profile, seed, config, warmup);
        DefaultSolver<EmployeeSchedule> defaultSolver = (DefaultSolver<EmployeeSchedule>) solver;
        AtomicLong bestReachedMs = new AtomicLong();
        AtomicLong bestReachedEvaluation = new AtomicLong();
        AtomicLong bestChangeCount = new AtomicLong();
        solver.addEventListener(event -> {
            bestReachedMs.set(event.getTimeMillisSpent());
            bestReachedEvaluation.set(defaultSolver.getSolverScope().getScoreCalculationCount());
            bestChangeCount.incrementAndGet();
        });

        resetHeapPeakUsage();
        long heapBefore = heapUsedBytes();
        long startedNanos = System.nanoTime();
        EmployeeSchedule solution = solver.solve(problem);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        long heapAfter = heapUsedBytes();
        long heapPeak = heapPeakUsedBytes();
        BendableScore bendableScore = Objects.requireNonNull(solution.getScore(), "OptaPlanner score");
        if (solution.getShiftList().stream().anyMatch(shift -> shift.getEmployee() == null)) {
            throw new IllegalStateException("OptaPlanner가 incomplete solution을 반환했습니다: " + bendableScore);
        }
        RosterScore score = OptaPlannerScoreAdapter.toRosterScore(bendableScore);
        long evaluations = defaultSolver.getSolverScope().getScoreCalculationCount();
        Long solverBestReachedMs = defaultSolver.getSolverScope().getBestSolutionTimeMillisSpent();

        return new RunResult(
                Engine.OPTAPLANNER,
                input.dataset(),
                input.sha256(),
                seed,
                repeat,
                profile,
                runOrder,
                score,
                elapsedMs,
                solverBestReachedMs == null ? bestReachedMs.get() : solverBestReachedMs,
                bestReachedEvaluation.get(),
                bestChangeCount.get(),
                evaluations,
                "score-calculation",
                0L,
                1L,
                "CONFIGURED_LIMIT_REACHED",
                heapBefore,
                heapAfter,
                heapPeak,
                assignmentFingerprint(solution),
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null);
    }

    private RunResult runPojoAlns(
            Engine candidateEngine,
            DatasetInput input,
            long seed,
            int repeat,
            Profile profile,
            int runOrder,
            BenchmarkConfig config,
            boolean warmup) {
        EmployeeSchedule schedule = scheduleBuilder.build(input.request());
        PlanningProblem problem = problemMapper.toPlanningProblem(schedule);
        AlnsSolverEngine engine = createPojoAlnsEngine(candidateEngine);
        long evaluationLimit = warmup
                ? Math.min(config.warmupEvaluationLimit(), config.pojoEvaluationLimit())
                : config.pojoEvaluationLimit();
        Duration spentLimit = Duration.ofSeconds(
                warmup ? Math.min(1L, config.wallClockSeconds()) : config.wallClockSeconds());
        SolveOptions options = profile == Profile.WALL_CLOCK
                ? SolveOptions.builder().spentLimit(spentLimit).randomSeed(seed).build()
                : SolveOptions.builder().maxEvaluations(evaluationLimit).randomSeed(seed).build();

        AtomicLong bestReachedMs = new AtomicLong();
        AtomicLong bestChangeCount = new AtomicLong();
        resetHeapPeakUsage();
        long heapBefore = heapUsedBytes();
        long startedNanos = System.nanoTime();
        SolveResult<RosterSolution> result = engine.solve(problem, options, ignored -> {
            bestReachedMs.set(Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
            bestChangeCount.incrementAndGet();
        });
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        long heapAfter = heapUsedBytes();
        long heapPeak = heapPeakUsedBytes();
        AlnsRunMetrics metrics = (AlnsRunMetrics) result.metrics();
        long bestReachedEvaluation = metrics.bestImprovements().isEmpty()
                ? 0L
                : metrics.bestImprovements().getLast().evaluation();
        RosterSolution solution = result.bestSolution();

        return new RunResult(
                candidateEngine,
                input.dataset(),
                input.sha256(),
                seed,
                repeat,
                profile,
                runOrder,
                result.score(),
                elapsedMs,
                bestReachedMs.get(),
                bestReachedEvaluation,
                bestChangeCount.get(),
                result.evaluationCount(),
                "complete-candidate",
                metrics.initialFeasibilityEvaluations(),
                result.iterations(),
                result.terminationReason().name(),
                heapBefore,
                heapAfter,
                heapPeak,
                solution == null ? null : assignmentFingerprint(problem, solution),
                metrics.rollbackAttemptCount(),
                metrics.rollbackFailureCount(),
                metrics.destroyFailures(),
                metrics.repairFailures(),
                metrics.operatorExceptions(),
                metrics.scoreMismatchFailures(),
                metrics.destroyOperators(),
                metrics.repairOperators(),
                metrics.bestImprovements(),
                null,
                null,
                null);
    }

    private static AlnsSolverEngine createPojoAlnsEngine(Engine candidateEngine) {
        if (candidateEngine == Engine.POJO_ALNS_BASELINE) {
            return new AlnsSolverEngine();
        }
        if (candidateEngine != Engine.POJO_ALNS_FAIRNESS_HOTSPOT) {
            throw new IllegalArgumentException("ALNS candidate engine이 아닙니다: " + candidateEngine);
        }

        FullScoreCalculator fullScoreCalculator = new FullScoreCalculator();
        Map<String, Set<String>> compatibility = new LinkedHashMap<>(
                OperatorCompatibilityMatrix.baseline().asMap());
        compatibility.put(
                FairnessHotspotRemoval.ID,
                Set.of(FairnessAwareRegret2Repair.ID));
        return new AlnsSolverEngine(
                new InitialSolutionBuilder(fullScoreCalculator),
                fullScoreCalculator,
                List.of(
                        new RandomRemoval(),
                        new RelatedShiftRemoval(),
                        new PreceptorRelationGroupRemoval(),
                        new FairnessHotspotRemoval()),
                List.of(
                        new GreedyRepair(),
                        new Regret2Repair(),
                        new RelationAwareRepair(),
                        new FairnessAwareRegret2Repair()),
                new OperatorCompatibilityMatrix(compatibility),
                new AlnsSolverConfig(
                        new AlnsIterationConfig(0.05d, 1, 8, 12, 2),
                        new SaAcceptanceConfig(0.2d, 0.01d, 10_000L),
                        new AdaptiveOperatorConfig(
                                1.0d, 0.1d, 0.2d, 100,
                                10.0d, 5.0d, 1.0d, 0.0d),
                        10_000L,
                        64,
                        Duration.ofMillis(10L)));
    }

    private RunResult runOptaStyleLocalMove(
            DatasetInput input,
            long seed,
            int repeat,
            Profile profile,
            int runOrder,
            BenchmarkConfig config,
            boolean warmup) {
        EmployeeSchedule schedule = scheduleBuilder.build(input.request());
        PlanningProblem problem = problemMapper.toPlanningProblem(schedule);
        OptaStyleLocalSearchEngine engine = new OptaStyleLocalSearchEngine();
        long evaluationLimit = warmup
                ? Math.min(config.warmupEvaluationLimit(), config.pojoEvaluationLimit())
                : config.pojoEvaluationLimit();
        Duration spentLimit = Duration.ofSeconds(
                warmup ? Math.min(1L, config.wallClockSeconds()) : config.wallClockSeconds());
        SolveOptions options = profile == Profile.WALL_CLOCK
                ? SolveOptions.builder().spentLimit(spentLimit).randomSeed(seed).build()
                : SolveOptions.builder().maxEvaluations(evaluationLimit).randomSeed(seed).build();

        AtomicLong bestReachedMs = new AtomicLong();
        AtomicLong bestChangeCount = new AtomicLong();
        resetHeapPeakUsage();
        long heapBefore = heapUsedBytes();
        long startedNanos = System.nanoTime();
        SolveResult<RosterSolution> result = engine.solve(problem, options, ignored -> {
            bestReachedMs.set(Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
            bestChangeCount.incrementAndGet();
        });
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        long heapAfter = heapUsedBytes();
        long heapPeak = heapPeakUsedBytes();
        OptaStyleLocalSearchMetrics metrics = (OptaStyleLocalSearchMetrics) result.metrics();
        long bestReachedEvaluation = metrics.bestImprovements().isEmpty()
                ? 0L
                : metrics.bestImprovements().getLast().evaluation();
        RosterSolution solution = result.bestSolution();

        return new RunResult(
                Engine.POJO_OPTA_STYLE_CHANGE_SWAP,
                input.dataset(),
                input.sha256(),
                seed,
                repeat,
                profile,
                runOrder,
                result.score(),
                elapsedMs,
                bestReachedMs.get(),
                bestReachedEvaluation,
                bestChangeCount.get(),
                result.evaluationCount(),
                "exact-move-candidate",
                metrics.initialFeasibilityEvaluations(),
                result.iterations(),
                result.terminationReason().name(),
                heapBefore,
                heapAfter,
                heapPeak,
                solution == null ? null : assignmentFingerprint(problem, solution),
                metrics.rollbackAttemptCount(),
                metrics.rollbackFailureCount(),
                0L,
                0L,
                0L,
                metrics.scoreMismatchFailures(),
                List.of(),
                List.of(),
                List.of(),
                metrics,
                null,
                null);
    }

    private RunResult runSequentialHybrid(
            Engine candidateEngine,
            DatasetInput input,
            long seed,
            int repeat,
            Profile profile,
            int runOrder,
            BenchmarkConfig config,
            boolean warmup) {
        EmployeeSchedule schedule = scheduleBuilder.build(input.request());
        PlanningProblem problem = problemMapper.toPlanningProblem(schedule);
        SequentialHybridSolverEngine.Order order = candidateEngine == Engine.POJO_HYBRID_OPTA_THEN_ALNS
                ? SequentialHybridSolverEngine.Order.OPTA_STYLE_THEN_ALNS
                : SequentialHybridSolverEngine.Order.ALNS_THEN_OPTA_STYLE;
        SequentialHybridSolverEngine engine = new SequentialHybridSolverEngine(order);
        long evaluationLimit = warmup ? Math.min(config.warmupEvaluationLimit(), config.pojoEvaluationLimit())
                : config.pojoEvaluationLimit();
        Duration spentLimit = Duration.ofSeconds(warmup ? Math.min(1L, config.wallClockSeconds()) : config.wallClockSeconds());
        SolveOptions options = profile == Profile.WALL_CLOCK
                ? SolveOptions.builder().spentLimit(spentLimit).randomSeed(seed).build()
                : SolveOptions.builder().maxEvaluations(evaluationLimit).randomSeed(seed).build();
        AtomicLong bestReachedMs = new AtomicLong();
        AtomicLong bestChangeCount = new AtomicLong();
        resetHeapPeakUsage();
        long heapBefore = heapUsedBytes();
        long startedNanos = System.nanoTime();
        SolveResult<RosterSolution> result = engine.solve(problem, options, ignored -> {
            bestReachedMs.set(Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
            bestChangeCount.incrementAndGet();
        });
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        long heapAfter = heapUsedBytes();
        long heapPeak = heapPeakUsedBytes();
        SequentialHybridMetrics metrics = (SequentialHybridMetrics) result.metrics();
        RosterSolution solution = result.bestSolution();
        return new RunResult(candidateEngine, input.dataset(), input.sha256(), seed, repeat, profile, runOrder,
                result.score(), elapsedMs, bestReachedMs.get(), result.evaluationCount(), bestChangeCount.get(),
                result.evaluationCount(), "stage-complete-candidate-sum", 0L, result.iterations(),
                result.terminationReason().name(), heapBefore, heapAfter, heapPeak,
                solution == null ? null : assignmentFingerprint(problem, solution), 0L,
                metrics.rollbackFailureCount(), 0L, 0L, 0L, metrics.scoreMismatchFailures(),
                List.of(), List.of(), List.of(), null, metrics, null);
    }

    private RunResult runFairnessRestrictedLocalMove(
            Engine candidateEngine,
            DatasetInput input,
            long seed,
            int repeat,
            Profile profile,
            int runOrder,
            BenchmarkConfig config,
            boolean warmup) {
        EmployeeSchedule schedule = scheduleBuilder.build(input.request());
        PlanningProblem problem = problemMapper.toPlanningProblem(schedule);
        FairnessRestrictedLocalSearchEngine engine = candidateEngine == Engine.POJO_FAIRNESS_HOTSPOT_PROTECTED_REASSIGN
                ? FairnessRestrictedLocalSearchEngine.hotspotGuidedProtectedReassign()
                : new FairnessRestrictedLocalSearchEngine();
        long evaluationLimit = warmup ? Math.min(config.warmupEvaluationLimit(), config.pojoEvaluationLimit())
                : config.pojoEvaluationLimit();
        Duration spentLimit = Duration.ofSeconds(warmup ? Math.min(1L, config.wallClockSeconds()) : config.wallClockSeconds());
        SolveOptions options = profile == Profile.WALL_CLOCK
                ? SolveOptions.builder().spentLimit(spentLimit).randomSeed(seed).build()
                : SolveOptions.builder().maxEvaluations(evaluationLimit).randomSeed(seed).build();
        AtomicLong bestReachedMs = new AtomicLong();
        AtomicLong bestChangeCount = new AtomicLong();
        resetHeapPeakUsage();
        long heapBefore = heapUsedBytes();
        long startedNanos = System.nanoTime();
        SolveResult<RosterSolution> result = engine.solve(problem, options, ignored -> {
            bestReachedMs.set(Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
            bestChangeCount.incrementAndGet();
        });
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        long heapAfter = heapUsedBytes();
        long heapPeak = heapPeakUsedBytes();
        FairnessRestrictedLocalSearchMetrics metrics = (FairnessRestrictedLocalSearchMetrics) result.metrics();
        RosterSolution solution = result.bestSolution();
        long bestEvaluation = metrics.bestImprovements().isEmpty() ? 0L
                : metrics.bestImprovements().getLast().evaluation();
        return new RunResult(candidateEngine, input.dataset(), input.sha256(), seed,
                repeat, profile, runOrder, result.score(), elapsedMs, bestReachedMs.get(), bestEvaluation,
                bestChangeCount.get(), result.evaluationCount(), "full-verified-move-candidate", metrics.initialFeasibilityEvaluations(),
                result.iterations(), result.terminationReason().name(), heapBefore, heapAfter, heapPeak,
                solution == null ? null : assignmentFingerprint(problem, solution), metrics.rollbackAttemptCount(),
                metrics.rollbackFailureCount(), 0L, 0L, 0L, metrics.scoreMismatchFailures(), List.of(), List.of(),
                List.of(), null, metrics, null);
    }

    private Solver<EmployeeSchedule> createOptaPlannerSolver(
            Profile profile,
            long seed,
            BenchmarkConfig config,
            boolean warmup) {
        TerminationConfig termination = switch (profile) {
            case WALL_CLOCK -> new TerminationConfig().withSpentLimit(Duration.ofSeconds(
                    warmup ? Math.min(1L, config.wallClockSeconds()) : config.wallClockSeconds()));
            case FIXED_EVALUATIONS -> new TerminationConfig().withScoreCalculationCountLimit(
                    warmup ? Math.min(config.warmupEvaluationLimit(), config.optaPlannerEvaluationLimit())
                            : config.optaPlannerEvaluationLimit());
        };
        SolverConfig solverConfig = new SolverConfig()
                .withSolutionClass(EmployeeSchedule.class)
                .withEntityClasses(Shift.class)
                .withConstraintProviderClass(EmployeeSchedulingConstraintProvider.class)
                .withTerminationConfig(termination)
                .withMoveThreadCount("NONE")
                .withEnvironmentMode(EnvironmentMode.REPRODUCIBLE)
                .withRandomSeed(seed);
        return SolverFactory.<EmployeeSchedule>create(solverConfig).buildSolver();
    }

    private DatasetInput loadDataset(String dataset) throws Exception {
        String resourcePath = "/json/" + dataset;
        byte[] bytes;
        try (InputStream input = getClass().getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalArgumentException("benchmark 입력을 찾을 수 없습니다: " + resourcePath);
            }
            bytes = input.readAllBytes();
        }
        return new DatasetInput(
                dataset,
                objectMapper.readValue(bytes, PlanningRequest.class),
                sha256(bytes));
    }

    private ObjectNode metadataRecord(BenchmarkConfig config) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("record_type", "metadata");
        json.put("schema_version", SCHEMA_VERSION);
        json.put("captured_at", Instant.now().toString());
        json.put("environment", config.environment());
        json.put("partition", config.partition());
        json.put("run_label", config.runLabel());
        json.put("operator_set", config.candidateEngine().operatorSet());
        json.put("candidate_engine", config.candidateEngine().name());
        json.put("os_name", System.getProperty("os.name"));
        json.put("os_version", System.getProperty("os.version"));
        json.put("os_arch", System.getProperty("os.arch"));
        json.put("java_version", System.getProperty("java.version"));
        json.put("java_vendor", System.getProperty("java.vendor"));
        json.put("available_processors", Runtime.getRuntime().availableProcessors());
        json.put("max_heap_bytes", Runtime.getRuntime().maxMemory());
        json.put("git_commit", gitOutput("rev-parse", "HEAD"));
        json.put("git_dirty", !gitOutput("status", "--porcelain", "--untracked-files=no").isBlank());
        json.put("warmup_excluded", config.warmup());
        json.put("execution_order_crossed", true);
        json.set("datasets", objectMapper.valueToTree(config.datasets()));
        json.set("seeds", objectMapper.valueToTree(config.seeds()));
        json.set("profiles", objectMapper.valueToTree(config.profiles().stream().map(Profile::id).toList()));
        json.put("repeats", config.repeats());
        json.put("wall_clock_seconds", config.wallClockSeconds());
        json.put("optaplanner_evaluation_limit", config.optaPlannerEvaluationLimit());
        json.put("pojo_evaluation_limit", config.pojoEvaluationLimit());
        json.put("fixed_evaluation_note",
                "OptaPlanner=score-calculation, POJO 후보=후보 평가; 단위가 달라 엔진 간 throughput 지표로 해석하지 않음");
        json.put("quantile_method", "nearest-rank-without-interpolation");
        json.put("config_fingerprint_sha256", config.fingerprint());
        return json;
    }

    private static List<Engine> crossedEngineOrder(int offset, Engine candidateEngine) {
        return Math.floorMod(offset, 2) == 0
                ? List.of(Engine.OPTAPLANNER, candidateEngine)
                : List.of(candidateEngine, Engine.OPTAPLANNER);
    }

    private void append(Path output, ObjectNode json) throws Exception {
        Files.writeString(output, objectMapper.writeValueAsString(json) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void prepareOutput(Path path) throws Exception {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String assignmentFingerprint(EmployeeSchedule solution) {
        String canonical = solution.getShiftList().stream()
                .sorted(Comparator.comparing(Shift::getId, Comparator.nullsFirst(Long::compareTo)))
                .map(shift -> String.valueOf(shift.getId()) + "="
                        + (shift.getEmployee() == null ? "<unassigned>" : shift.getEmployee().getId()))
                .collect(Collectors.joining("\n"));
        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static String assignmentFingerprint(PlanningProblem problem, RosterSolution solution) {
        StringBuilder canonical = new StringBuilder();
        for (int shiftIndex = 0; shiftIndex < problem.shiftCount(); shiftIndex++) {
            if (shiftIndex > 0) {
                canonical.append('\n');
            }
            canonical.append(problem.shifts().get(shiftIndex).planningId())
                    .append('=')
                    .append(problem.employees().get(solution.employeeIndex(shiftIndex)).externalId());
        }
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void resetHeapPeakUsage() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
    }

    private static long heapUsedBytes() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long heapPeakUsedBytes() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .mapToLong(pool -> pool.getPeakUsage().getUsed())
                .sum();
    }

    private static String gitOutput(String... arguments) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(Arrays.asList(arguments));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(
                    process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 ? output : "unknown";
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256을 계산할 수 없습니다.", exception);
        }
    }

    private enum Engine {
        OPTAPLANNER,
        POJO_ALNS_BASELINE,
        POJO_ALNS_FAIRNESS_HOTSPOT,
        POJO_OPTA_STYLE_CHANGE_SWAP,
        POJO_HYBRID_OPTA_THEN_ALNS,
        POJO_HYBRID_ALNS_THEN_OPTA,
        POJO_FAIRNESS_RESTRICTED_CHANGE_SWAP,
        POJO_FAIRNESS_HOTSPOT_PROTECTED_REASSIGN;

        String operatorSet() {
            return switch (this) {
                case OPTAPLANNER -> "N/A";
                case POJO_ALNS_BASELINE -> "BASELINE_ONLY";
                case POJO_ALNS_FAIRNESS_HOTSPOT ->
                        "BASELINE_PLUS_FAIRNESS_HOTSPOT_PAIR";
                case POJO_OPTA_STYLE_CHANGE_SWAP -> "OPTA_STYLE_CHANGE_SWAP_ONLY";
                case POJO_HYBRID_OPTA_THEN_ALNS -> "OPTA_STYLE_THEN_ALNS_SEQUENTIAL";
                case POJO_HYBRID_ALNS_THEN_OPTA -> "ALNS_THEN_OPTA_STYLE_SEQUENTIAL";
                case POJO_FAIRNESS_RESTRICTED_CHANGE_SWAP -> "FAIRNESS_RESTRICTED_CHANGE_SWAP";
                case POJO_FAIRNESS_HOTSPOT_PROTECTED_REASSIGN ->
                        "FAIRNESS_HOTSPOT_GUIDED_PROTECTED_REASSIGN";
            };
        }

        boolean isAlns() {
            return this == POJO_ALNS_BASELINE || this == POJO_ALNS_FAIRNESS_HOTSPOT;
        }

        static Engine candidateParse(String value) {
            Engine parsed;
            try {
                parsed = Engine.valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(
                        "phase6.benchmark.candidate-engine가 올바르지 않습니다: " + value, invalid);
            }
            if (parsed == OPTAPLANNER) {
                throw new IllegalArgumentException("OptaPlanner는 Phase 6 benchmark의 candidate가 될 수 없습니다.");
            }
            return parsed;
        }
    }

    private enum Profile {
        WALL_CLOCK("wall-clock"),
        FIXED_EVALUATIONS("fixed-evaluations");

        private final String id;

        Profile(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }

        static Profile parse(String value) {
            return switch (value.strip().toLowerCase(Locale.ROOT).replace('_', '-')) {
                case "wall-clock" -> WALL_CLOCK;
                case "fixed-evaluations" -> FIXED_EVALUATIONS;
                default -> throw new IllegalArgumentException(
                        "알 수 없는 benchmark profile: " + value);
            };
        }
    }

    private record DatasetInput(String dataset, PlanningRequest request, String sha256) {
    }

    private record BenchmarkConfig(
            List<String> datasets,
            List<Long> seeds,
            int repeats,
            List<Profile> profiles,
            long wallClockSeconds,
            long optaPlannerEvaluationLimit,
            long pojoEvaluationLimit,
            long warmupEvaluationLimit,
            Engine candidateEngine,
            boolean warmup,
            String environment,
            String partition,
            String runLabel,
            Path output,
            Path summaryOutput,
            Path reportOutput) {

        static BenchmarkConfig fromSystemProperties() {
            List<String> datasets = csvProperty("phase6.benchmark.datasets", DEFAULT_DATASETS);
            List<Long> seeds = csvProperty(
                    "phase6.benchmark.seeds",
                    DEFAULT_SEEDS.stream().map(String::valueOf).toList())
                    .stream().map(Long::parseLong).toList();
            List<Profile> profiles = csvProperty(
                    "phase6.benchmark.profiles",
                    List.of("wall-clock"))
                    .stream().map(Profile::parse).toList();
            int repeats = Integer.getInteger("phase6.benchmark.repeats", 2);
            long wallClockSeconds = Long.getLong("phase6.benchmark.wall-clock-seconds", 60L);
            long optaPlannerEvaluationLimit = Long.getLong(
                    "phase6.benchmark.optaplanner-evaluation-limit", 1_000L);
            long pojoEvaluationLimit = Long.getLong(
                    "phase6.benchmark.pojo-evaluation-limit", 1_000L);
            long warmupEvaluationLimit = Long.getLong(
                    "phase6.benchmark.warmup-evaluation-limit", 10L);
            Engine candidateEngine = Engine.candidateParse(System.getProperty(
                    "phase6.benchmark.candidate-engine", "POJO_ALNS_BASELINE"));
            boolean warmup = Boolean.parseBoolean(
                    System.getProperty("phase6.benchmark.warmup", "true"));
            String environment = System.getProperty(
                    "phase6.benchmark.environment", "macos-local").strip();
            String partition = System.getProperty(
                    "phase6.benchmark.partition", "holdout").strip();
            String runLabel = System.getProperty(
                    "phase6.benchmark.run-label", "phase6-baseline").strip();
            Path output = Path.of(System.getProperty(
                    "phase6.benchmark.output",
                    "target/benchmarks/phase6/" + partition + "/raw.jsonl"));
            Path summaryOutput = Path.of(System.getProperty(
                    "phase6.benchmark.summary-output",
                    "target/benchmarks/phase6/" + partition + "/summary.json"));
            Path reportOutput = Path.of(System.getProperty(
                    "phase6.benchmark.report-output",
                    "target/benchmarks/phase6/" + partition + "/report.md"));

            if (datasets.isEmpty() || seeds.isEmpty() || profiles.isEmpty()) {
                throw new IllegalArgumentException("dataset, seed, profile은 각각 하나 이상이어야 합니다.");
            }
            if (repeats < 1 || wallClockSeconds < 1
                    || optaPlannerEvaluationLimit < 1
                    || pojoEvaluationLimit < 1
                    || warmupEvaluationLimit < 1) {
                throw new IllegalArgumentException("repeats와 benchmark 예산은 1 이상이어야 합니다.");
            }
            if (!environment.equals("macos-local") && !environment.equals("container")) {
                throw new IllegalArgumentException(
                        "phase6.benchmark.environment는 macos-local 또는 container여야 합니다.");
            }
            if (partition.isBlank() || runLabel.isBlank()) {
                throw new IllegalArgumentException("partition과 run-label은 비어 있을 수 없습니다.");
            }
            return new BenchmarkConfig(
                    datasets,
                    seeds,
                    repeats,
                    profiles,
                    wallClockSeconds,
                    optaPlannerEvaluationLimit,
                    pojoEvaluationLimit,
                    warmupEvaluationLimit,
                    candidateEngine,
                    warmup,
                    environment,
                    partition,
                    runLabel,
                    output,
                    summaryOutput,
                    reportOutput);
        }

        String fingerprint() {
            return sha256(String.join("|",
                    environment,
                    partition,
                    runLabel,
                    datasets.toString(),
                    seeds.toString(),
                    profiles.toString(),
                    Integer.toString(repeats),
                    Long.toString(wallClockSeconds),
                    Long.toString(optaPlannerEvaluationLimit),
                    Long.toString(pojoEvaluationLimit),
                    "OPTAPLANNER:REPRODUCIBLE:NONE",
                    candidateEngine.name(),
                    candidateEngine.operatorSet()).getBytes(StandardCharsets.UTF_8));
        }

        private static <T> List<String> csvProperty(String name, List<T> defaults) {
            String defaultValue = defaults.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));
            return Arrays.stream(System.getProperty(name, defaultValue).split(","))
                    .map(String::strip)
                    .filter(value -> !value.isEmpty())
                    .toList();
        }
    }

    private record RunResult(
            Engine engine,
            String dataset,
            String inputSha256,
            long seed,
            int repeat,
            Profile profile,
            int runOrder,
            RosterScore score,
            long elapsedMs,
            long bestReachedMs,
            long bestReachedEvaluation,
            long bestSolutionChangeCount,
            long evaluationCount,
            String evaluationUnit,
            long initialFeasibilityEvaluationCount,
            long iterationCount,
            String terminationReason,
            long heapUsedBefore,
            long heapUsedAfter,
            long heapPeakUsed,
            String assignmentFingerprint,
            long rollbackAttempts,
            long rollbackFailures,
            long destroyFailures,
            long repairFailures,
            long operatorExceptions,
            long scoreMismatchFailures,
            List<OperatorStatistics> destroyOperators,
            List<OperatorStatistics> repairOperators,
            List<AlnsRunMetrics.BestImprovement> bestImprovements,
            OptaStyleLocalSearchMetrics localMoveMetrics,
            Object researchMetrics,
            String errorType) {

        RunResult {
            destroyOperators = List.copyOf(destroyOperators);
            repairOperators = List.copyOf(repairOperators);
            bestImprovements = List.copyOf(bestImprovements);
        }

        static RunResult failure(
                Engine engine,
                String dataset,
                String inputSha256,
                long seed,
                int repeat,
                Profile profile,
                int runOrder,
                long elapsedMs,
                String errorType) {
            return new RunResult(
                    engine, dataset, inputSha256, seed, repeat, profile, runOrder,
                    null, elapsedMs, 0L, 0L, 0L, 0L, "unknown", 0L, 0L,
                    "EXCEPTION", 0L, 0L, 0L, null,
                    0L, 0L, 0L, 0L, 0L, 0L,
                    List.of(), List.of(), List.of(), null, null, errorType);
        }

        boolean executionFailed() {
            return errorType != null;
        }

        boolean feasible() {
            return score != null && score.isFeasible();
        }

        ObjectNode toJson(ObjectMapper mapper, BenchmarkConfig config) {
            ObjectNode json = mapper.createObjectNode();
            json.put("record_type", "run");
            json.put("schema_version", SCHEMA_VERSION);
            json.put("captured_at", Instant.now().toString());
            json.put("environment", config.environment());
            json.put("partition", config.partition());
            json.put("run_label", config.runLabel());
            json.put("engine", engine.name());
            json.put("operator_set", engine.operatorSet());
            json.put("dataset", dataset);
            json.put("input_sha256", inputSha256);
            json.put("seed", seed);
            json.put("repeat", repeat);
            json.put("profile", profile.id());
            json.put("run_order", runOrder);
            if (profile == Profile.WALL_CLOCK) {
                json.put("wall_clock_limit_ms", Duration.ofSeconds(config.wallClockSeconds()).toMillis());
                json.putNull("evaluation_limit");
            } else {
                json.putNull("wall_clock_limit_ms");
                json.put("evaluation_limit", engine == Engine.OPTAPLANNER
                        ? config.optaPlannerEvaluationLimit()
                        : config.pojoEvaluationLimit());
            }
            putScore(json, score);
            json.put("complete", score != null && assignmentFingerprint != null);
            json.put("elapsed_ms", elapsedMs);
            json.put("best_reached_ms", bestReachedMs);
            json.put("best_reached_evaluation", bestReachedEvaluation);
            json.put("best_solution_change_count", bestSolutionChangeCount);
            json.put("evaluation_count", evaluationCount);
            json.put("evaluation_unit", evaluationUnit);
            json.put("initial_feasibility_evaluation_count", initialFeasibilityEvaluationCount);
            json.put("iteration_count", iterationCount);
            json.put("termination_reason", terminationReason);
            json.put("heap_used_before_bytes", heapUsedBefore);
            json.put("heap_used_after_bytes", heapUsedAfter);
            json.put("heap_used_delta_bytes", heapUsedAfter - heapUsedBefore);
            json.put("heap_peak_used_bytes", heapPeakUsed);
            if (assignmentFingerprint == null) {
                json.putNull("assignment_fingerprint_sha256");
            } else {
                json.put("assignment_fingerprint_sha256", assignmentFingerprint);
            }
            json.put("rollback_attempt_count", rollbackAttempts);
            json.put("rollback_failure_count", rollbackFailures);
            json.put("state_corruption_count", rollbackFailures);
            json.put("destroy_failure_count", destroyFailures);
            json.put("repair_failure_count", repairFailures);
            json.put("operator_exception_count", operatorExceptions);
            json.put("score_mismatch_count", scoreMismatchFailures);
            json.set("destroy_operators", mapper.valueToTree(destroyOperators));
            json.set("repair_operators", mapper.valueToTree(repairOperators));
            json.set("best_improvements", mapper.valueToTree(bestImprovements));
            if (localMoveMetrics == null) {
                json.putNull("local_move_metrics");
            } else {
                json.set("local_move_metrics", mapper.valueToTree(localMoveMetrics));
            }
            if (researchMetrics == null) {
                json.putNull("research_metrics");
            } else {
                json.set("research_metrics", mapper.valueToTree(researchMetrics));
            }
            if (errorType == null) {
                json.putNull("error_type");
            } else {
                json.put("error_type", errorType);
            }
            return json;
        }
    }

    private record PairRun(
            String dataset,
            long seed,
            int repeat,
            Profile profile,
            Engine firstEngine,
            RunResult baseline,
            RunResult candidate) {

        List<RunResult> runs() {
            return List.of(baseline, candidate);
        }

        boolean comparable() {
            return baseline.score() != null && candidate.score() != null;
        }

        ObjectNode toJson(ObjectMapper mapper, BenchmarkConfig config) {
            ObjectNode json = mapper.createObjectNode();
            json.put("record_type", "pair");
            json.put("schema_version", SCHEMA_VERSION);
            json.put("environment", config.environment());
            json.put("partition", config.partition());
            json.put("dataset", dataset);
            json.put("seed", seed);
            json.put("repeat", repeat);
            json.put("profile", profile.id());
            json.put("first_engine", firstEngine.name());
            json.put("comparable", comparable());
            if (!comparable()) {
                json.putNull("candidate_outcome");
            } else {
                int comparison = candidate.score().compareTo(baseline.score());
                json.put("candidate_outcome", comparison > 0 ? "WIN" : comparison < 0 ? "LOSS" : "TIE");
                json.put("hard_delta", candidate.score().hardDeltaFrom(baseline.score()));
                ArrayNode softDelta = json.putArray("soft_delta");
                for (int level = 0; level < RosterScore.SOFT_LEVELS; level++) {
                    softDelta.add(candidate.score().softDeltaFrom(baseline.score(), level));
                }
            }
            return json;
        }
    }

    private static final class BenchmarkReport {

        private final BenchmarkConfig config;
        private final List<SummaryGroup> groups;
        private final List<OperatorAggregate> destroyOperators;
        private final List<OperatorAggregate> repairOperators;
        private final LocalMoveAggregate localMoveAggregate;
        private final long executionFailures;

        private BenchmarkReport(
                BenchmarkConfig config,
                List<SummaryGroup> groups,
                List<OperatorAggregate> destroyOperators,
                List<OperatorAggregate> repairOperators,
                LocalMoveAggregate localMoveAggregate,
                long executionFailures) {
            this.config = config;
            this.groups = List.copyOf(groups);
            this.destroyOperators = List.copyOf(destroyOperators);
            this.repairOperators = List.copyOf(repairOperators);
            this.localMoveAggregate = localMoveAggregate;
            this.executionFailures = executionFailures;
        }

        static BenchmarkReport from(BenchmarkConfig config, List<PairRun> pairs) {
            List<SummaryGroup> groups = new ArrayList<>();
            for (Profile profile : config.profiles()) {
                List<PairRun> profilePairs = pairs.stream()
                        .filter(pair -> pair.profile() == profile)
                        .toList();
                groups.add(SummaryGroup.from(profile, "ALL", profilePairs, config));
                for (String dataset : config.datasets()) {
                    groups.add(SummaryGroup.from(
                            profile,
                            dataset,
                            profilePairs.stream()
                                    .filter(pair -> pair.dataset().equals(dataset))
                                    .toList(),
                            config));
                }
            }
            List<RunResult> pojoRuns = pairs.stream().map(PairRun::candidate).toList();
            return new BenchmarkReport(
                    config,
                    groups,
                    config.candidateEngine().isAlns()
                            ? aggregateOperators(pojoRuns, true) : List.of(),
                    config.candidateEngine().isAlns()
                            ? aggregateOperators(pojoRuns, false) : List.of(),
                    config.candidateEngine() == Engine.POJO_OPTA_STYLE_CHANGE_SWAP
                            ? LocalMoveAggregate.from(pojoRuns) : null,
                    pairs.stream().flatMap(pair -> pair.runs().stream())
                            .filter(RunResult::executionFailed).count());
        }

        void writeJson(ObjectMapper mapper, Path output) throws Exception {
            Files.createDirectories(output.toAbsolutePath().getParent());
            ObjectNode root = mapper.createObjectNode();
            root.put("schema_version", SCHEMA_VERSION);
            root.put("environment", config.environment());
            root.put("partition", config.partition());
            root.put("run_label", config.runLabel());
            root.put("candidate_engine", config.candidateEngine().name());
            root.put("operator_set", config.candidateEngine().operatorSet());
            root.put("quantile_method", "nearest-rank-without-interpolation");
            root.put("config_fingerprint_sha256", config.fingerprint());
            root.put("execution_failure_count", executionFailures);
            root.set("groups", mapper.valueToTree(groups));
            root.set("destroy_operator_contribution", mapper.valueToTree(destroyOperators));
            root.set("repair_operator_contribution", mapper.valueToTree(repairOperators));
            if (localMoveAggregate == null) {
                root.putNull("local_move_contribution");
            } else {
                root.set("local_move_contribution", mapper.valueToTree(localMoveAggregate));
            }
            Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root)
                    + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        void writeMarkdown(Path output) throws Exception {
            Files.createDirectories(output.toAbsolutePath().getParent());
            StringBuilder markdown = new StringBuilder();
            markdown.append("# Phase 6 paired benchmark\n\n")
                    .append("- 환경: `").append(config.environment()).append("`\n")
                    .append("- partition: `").append(config.partition()).append("`\n")
                    .append("- run label: `").append(config.runLabel()).append("`\n")
                    .append("- candidate engine: `").append(config.candidateEngine()).append("`\n")
                    .append("- operator set: `").append(config.candidateEngine().operatorSet()).append("`\n")
                    .append("- quantile: nearest-rank, 보간 없음\n")
                    .append("- 실행 순서: 입력/seed/repeat/profile마다 교차\n")
                    .append("- warm-up: ")
                    .append(config.warmup() ? "실행, 집계 제외\n" : "미실행\n")
                    .append("- fixed-evaluation 단위: OptaPlanner는 score calculation, ")
                    .append("POJO 후보는 candidate evaluation이므로 엔진 간 throughput으로 직접 비교하지 않음\n\n")
                    .append("## 품질·tail 요약\n\n")
                    .append("| profile | dataset | pairs | feasible Opta/POJO | W/T/L | ")
                    .append("Opta score p10/median/p90 | POJO score p10/median/p90 | ")
                    .append("paired delta p10/median/p90 | elapsed p95 Opta/POJO | ")
                    .append("eval p10/p50/p90 Opta/POJO | best eval p50/p90 POJO | rollback fail |\n")
                    .append("|---|---|---:|---:|---:|---|---|---|---:|---|---:|---:|\n");
            for (SummaryGroup group : groups) {
                markdown.append("| ").append(group.profile())
                        .append(" | ").append(group.dataset())
                        .append(" | ").append(group.pairCount())
                        .append(" | ").append(group.baselineFeasibleCount()).append('/')
                        .append(group.candidateFeasibleCount())
                        .append(" | ").append(group.wins()).append('/')
                        .append(group.ties()).append('/').append(group.losses())
                        .append(" | ").append(group.baselineScoreP10()).append(" / ")
                        .append(group.baselineScoreMedian()).append(" / ")
                        .append(group.baselineScoreP90())
                        .append(" | ").append(group.candidateScoreP10()).append(" / ")
                        .append(group.candidateScoreMedian()).append(" / ")
                        .append(group.candidateScoreP90())
                        .append(" | ").append(Arrays.toString(group.pairedDeltaP10())).append(" / ")
                        .append(Arrays.toString(group.pairedDeltaMedian())).append(" / ")
                        .append(Arrays.toString(group.pairedDeltaP90()))
                        .append(" | ").append(group.baselineElapsedP95Ms()).append("/")
                        .append(group.candidateElapsedP95Ms())
                        .append(" | ")
                        .append(group.baselineEvaluationP10()).append('/')
                        .append(group.baselineEvaluationP50()).append('/')
                        .append(group.baselineEvaluationP90()).append(" / ")
                        .append(group.candidateEvaluationP10()).append('/')
                        .append(group.candidateEvaluationP50()).append('/')
                        .append(group.candidateEvaluationP90())
                        .append(" | ").append(group.candidateBestEvaluationP50()).append("/")
                        .append(group.candidateBestEvaluationP90())
                        .append(" | ").append(group.rollbackFailureCount())
                        .append(" |\n");
            }
            markdown.append("\n점수 분위수는 전체 `RosterScore`를 사전식으로 정렬해 실제 vector를 선택합니다. ")
                    .append("paired delta 배열은 `[hard, soft0, soft1, soft2, soft3]`의 ")
                    .append("`POJO - OptaPlanner` 좌표별 nearest-rank입니다.\n\n")
                    .append("## 탐색 선택 분포·best 기여\n\n");
            if (config.candidateEngine().isAlns()) {
                markdown.append("### Destroy\n\n")
                        .append(operatorTable(destroyOperators))
                        .append("\n### Repair\n\n")
                        .append(operatorTable(repairOperators))
                        .append("\n`final best contribution runs`는 해당 run의 마지막 global-best 개선을 ")
                        .append("만든 operator가 이 operator였던 실행 수입니다.\n\n");
            } else {
                markdown.append(localMoveTable(localMoveAggregate)).append('\n');
            }
            markdown.append("## 수치 결정 체크리스트\n\n")
                    .append("이 보고서는 승인 수치를 자동으로 만들지 않습니다. holdout 결과를 근거로 ")
                    .append("다음 값을 후속 결정해야 합니다.\n\n")
                    .append("- paired loss 허용 개수 또는 최소 non-loss 비율\n")
                    .append("- 상위 soft level별 p10 허용 열화량\n")
                    .append("- best 도달 평가 횟수의 최소 개선량\n")
                    .append("- wall-clock p95 상대/절대 허용 한계\n")
                    .append("- rollback failure 및 score mismatch 허용 건수\n")
                    .append("- 모든 운영 입력·seed의 hard score `0` 요구 유지 여부\n\n")
                    .append("Execution failures: ").append(executionFailures).append("\n");
            Files.writeString(output, markdown.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        private static String operatorTable(List<OperatorAggregate> operators) {
            StringBuilder table = new StringBuilder()
                    .append("| operator | selections | global best events | current improvements | ")
                    .append("accepted worsening | rejections | final best contribution runs |\n")
                    .append("|---|---:|---:|---:|---:|---:|---:|\n");
            for (OperatorAggregate operator : operators) {
                table.append("| ").append(operator.operatorId())
                        .append(" | ").append(operator.selectionCount())
                        .append(" | ").append(operator.globalBestCount())
                        .append(" | ").append(operator.currentImprovementCount())
                        .append(" | ").append(operator.acceptedWorseningCount())
                        .append(" | ").append(operator.rejectionCount())
                        .append(" | ").append(operator.finalBestContributionRuns())
                        .append(" |\n");
            }
            return table.toString();
        }

        private static List<OperatorAggregate> aggregateOperators(
                List<RunResult> runs, boolean destroy) {
            Map<String, MutableOperatorAggregate> aggregates = new LinkedHashMap<>();
            for (RunResult run : runs) {
                List<OperatorStatistics> statistics = destroy
                        ? run.destroyOperators() : run.repairOperators();
                for (OperatorStatistics statistic : statistics) {
                    aggregates.computeIfAbsent(
                            statistic.operatorId(), MutableOperatorAggregate::new)
                            .add(statistic);
                }
                if (!run.bestImprovements().isEmpty()) {
                    AlnsRunMetrics.BestImprovement last = run.bestImprovements().getLast();
                    String operatorId = destroy
                            ? last.destroyOperatorId() : last.repairOperatorId();
                    aggregates.computeIfAbsent(operatorId, MutableOperatorAggregate::new)
                            .finalBestContributionRuns++;
                }
            }
            return aggregates.values().stream()
                    .map(MutableOperatorAggregate::snapshot)
                    .sorted(Comparator.comparing(OperatorAggregate::operatorId))
                    .toList();
        }

        private static String localMoveTable(LocalMoveAggregate aggregate) {
            if (aggregate == null) {
                return "local-move metrics가 없습니다.\n";
            }
            return new StringBuilder()
                    .append("| move | evaluated | accepted step | rejected | global best events | ")
                    .append("final best contribution runs |\n")
                    .append("|---|---:|---:|---:|---:|---:|\n")
                    .append("| REASSIGN | ").append(aggregate.changeCandidates())
                    .append(" | ").append(aggregate.acceptedChangeSteps())
                    .append(" | ").append(aggregate.rejectedChangeCandidates())
                    .append(" | ").append(aggregate.changeBestEvents())
                    .append(" | ").append(aggregate.changeFinalBestRuns()).append(" |\n")
                    .append("| SWAP | ").append(aggregate.swapCandidates())
                    .append(" | ").append(aggregate.acceptedSwapSteps())
                    .append(" | ").append(aggregate.rejectedSwapCandidates())
                    .append(" | ").append(aggregate.swapBestEvents())
                    .append(" | ").append(aggregate.swapFinalBestRuns()).append(" |\n")
                    .append("\n- history length: `").append(aggregate.historyLength())
                    .append("`\n- full verification interval: `").append(aggregate.fullVerificationInterval())
                    .append("`\n- cancelled candidates: `").append(aggregate.cancelledCandidates())
                    .append("`\n- full verifications: `").append(aggregate.fullVerificationCount())
                    .append("`\n")
                    .toString();
        }
    }

    private record LocalMoveAggregate(
            int historyLength,
            int fullVerificationInterval,
            long changeCandidates,
            long swapCandidates,
            long acceptedChangeSteps,
            long acceptedSwapSteps,
            long rejectedChangeCandidates,
            long rejectedSwapCandidates,
            long changeBestEvents,
            long swapBestEvents,
            long changeFinalBestRuns,
            long swapFinalBestRuns,
            long cancelledCandidates,
            long fullVerificationCount) {

        static LocalMoveAggregate from(List<RunResult> runs) {
            int historyLength = 0;
            int fullVerificationInterval = 0;
            long changeCandidates = 0L;
            long swapCandidates = 0L;
            long acceptedChangeSteps = 0L;
            long acceptedSwapSteps = 0L;
            long rejectedChangeCandidates = 0L;
            long rejectedSwapCandidates = 0L;
            long changeBestEvents = 0L;
            long swapBestEvents = 0L;
            long changeFinalBestRuns = 0L;
            long swapFinalBestRuns = 0L;
            long cancelledCandidates = 0L;
            long fullVerificationCount = 0L;

            for (RunResult run : runs) {
                OptaStyleLocalSearchMetrics metrics = run.localMoveMetrics();
                if (metrics == null) {
                    continue;
                }
                if (historyLength == 0) {
                    historyLength = metrics.historyLength();
                } else if (historyLength != metrics.historyLength()) {
                    throw new IllegalStateException("Opta-style local move history length가 run마다 다릅니다.");
                }
                if (fullVerificationInterval == 0) {
                    fullVerificationInterval = metrics.fullVerificationInterval();
                } else if (fullVerificationInterval != metrics.fullVerificationInterval()) {
                    throw new IllegalStateException("Opta-style local move full verification interval이 run마다 다릅니다.");
                }
                changeCandidates += metrics.changeCandidates();
                swapCandidates += metrics.swapCandidates();
                acceptedChangeSteps += metrics.acceptedChangeSteps();
                acceptedSwapSteps += metrics.acceptedSwapSteps();
                rejectedChangeCandidates += metrics.rejectedChangeCandidates();
                rejectedSwapCandidates += metrics.rejectedSwapCandidates();
                cancelledCandidates += metrics.cancelledCandidates();
                fullVerificationCount += metrics.fullVerificationCount();
                for (OptaStyleLocalSearchMetrics.BestImprovement improvement : metrics.bestImprovements()) {
                    if ("REASSIGN".equals(improvement.moveType())) {
                        changeBestEvents++;
                    } else if ("SWAP".equals(improvement.moveType())) {
                        swapBestEvents++;
                    }
                }
                if (!metrics.bestImprovements().isEmpty()) {
                    String lastMove = metrics.bestImprovements().getLast().moveType();
                    if ("REASSIGN".equals(lastMove)) {
                        changeFinalBestRuns++;
                    } else if ("SWAP".equals(lastMove)) {
                        swapFinalBestRuns++;
                    }
                }

            }
            return new LocalMoveAggregate(
                    historyLength,
                    fullVerificationInterval,
                    changeCandidates,
                    swapCandidates,
                    acceptedChangeSteps,
                    acceptedSwapSteps,
                    rejectedChangeCandidates,
                    rejectedSwapCandidates,
                    changeBestEvents,
                    swapBestEvents,
                    changeFinalBestRuns,
                    swapFinalBestRuns,
                    cancelledCandidates,
                    fullVerificationCount);
        }
    }

    private record SummaryGroup(
            String profile,
            String dataset,
            long pairCount,
            long comparablePairCount,
            long baselineFeasibleCount,
            long candidateFeasibleCount,
            long wins,
            long ties,
            long losses,
            String baselineScoreP10,
            String baselineScoreMedian,
            String baselineScoreP90,
            String candidateScoreP10,
            String candidateScoreMedian,
            String candidateScoreP90,
            long[] pairedDeltaP10,
            long[] pairedDeltaMedian,
            long[] pairedDeltaP90,
            long baselineElapsedP95Ms,
            long candidateElapsedP95Ms,
            long baselineElapsedMaxMs,
            long candidateElapsedMaxMs,
            long baselineEvaluationP10,
            long baselineEvaluationP50,
            long baselineEvaluationP90,
            long candidateEvaluationP10,
            long candidateEvaluationP50,
            long candidateEvaluationP90,
            long candidateBestEvaluationP50,
            long candidateBestEvaluationP90,
            long candidateBestReachedP95Ms,
            long rollbackAttemptCount,
            long rollbackFailureCount,
            long destroyFailureCount,
            long repairFailureCount,
            long operatorExceptionCount,
            long scoreMismatchCount,
            long baselineExecutionFailureCount,
            long candidateExecutionFailureCount,
            long wallClockDeadlineOverrunCount) {

        static SummaryGroup from(
                Profile profile,
                String dataset,
                List<PairRun> pairs,
                BenchmarkConfig config) {
            List<RunResult> baselineRuns = pairs.stream().map(PairRun::baseline).toList();
            List<RunResult> candidateRuns = pairs.stream().map(PairRun::candidate).toList();
            List<RosterScore> baselineScores = baselineRuns.stream()
                    .map(RunResult::score).filter(Objects::nonNull).toList();
            List<RosterScore> candidateScores = candidateRuns.stream()
                    .map(RunResult::score).filter(Objects::nonNull).toList();
            List<ScorePair> scorePairs = pairs.stream()
                    .filter(PairRun::comparable)
                    .map(pair -> new ScorePair(pair.baseline().score(), pair.candidate().score()))
                    .toList();
            WinTieLoss wtl = scorePairs.isEmpty()
                    ? new WinTieLoss(0L, 0L, 0L)
                    : Phase6BenchmarkStatistics.winTieLoss(scorePairs);
            long wallClockLimitMs = Duration.ofSeconds(config.wallClockSeconds()).toMillis();

            return new SummaryGroup(
                    profile.id(),
                    dataset,
                    pairs.size(),
                    scorePairs.size(),
                    baselineRuns.stream().filter(RunResult::feasible).count(),
                    candidateRuns.stream().filter(RunResult::feasible).count(),
                    wtl.wins(),
                    wtl.ties(),
                    wtl.losses(),
                    scoreQuantile(baselineScores, 0.10d),
                    scoreQuantile(baselineScores, 0.50d),
                    scoreQuantile(baselineScores, 0.90d),
                    scoreQuantile(candidateScores, 0.10d),
                    scoreQuantile(candidateScores, 0.50d),
                    scoreQuantile(candidateScores, 0.90d),
                    deltaQuantile(scorePairs, 0.10d),
                    deltaQuantile(scorePairs, 0.50d),
                    deltaQuantile(scorePairs, 0.90d),
                    longQuantile(baselineRuns.stream().map(RunResult::elapsedMs).toList(), 0.95d),
                    longQuantile(candidateRuns.stream().map(RunResult::elapsedMs).toList(), 0.95d),
                    baselineRuns.stream().mapToLong(RunResult::elapsedMs).max().orElse(0L),
                    candidateRuns.stream().mapToLong(RunResult::elapsedMs).max().orElse(0L),
                    longQuantile(baselineRuns.stream()
                            .map(RunResult::evaluationCount).toList(), 0.10d),
                    longQuantile(baselineRuns.stream()
                            .map(RunResult::evaluationCount).toList(), 0.50d),
                    longQuantile(baselineRuns.stream()
                            .map(RunResult::evaluationCount).toList(), 0.90d),
                    longQuantile(candidateRuns.stream()
                            .map(RunResult::evaluationCount).toList(), 0.10d),
                    longQuantile(candidateRuns.stream()
                            .map(RunResult::evaluationCount).toList(), 0.50d),
                    longQuantile(candidateRuns.stream()
                            .map(RunResult::evaluationCount).toList(), 0.90d),
                    longQuantile(candidateRuns.stream()
                            .map(RunResult::bestReachedEvaluation).toList(), 0.50d),
                    longQuantile(candidateRuns.stream()
                            .map(RunResult::bestReachedEvaluation).toList(), 0.90d),
                    longQuantile(candidateRuns.stream()
                            .map(RunResult::bestReachedMs).toList(), 0.95d),
                    candidateRuns.stream().mapToLong(RunResult::rollbackAttempts).sum(),
                    candidateRuns.stream().mapToLong(RunResult::rollbackFailures).sum(),
                    candidateRuns.stream().mapToLong(RunResult::destroyFailures).sum(),
                    candidateRuns.stream().mapToLong(RunResult::repairFailures).sum(),
                    candidateRuns.stream().mapToLong(RunResult::operatorExceptions).sum(),
                    candidateRuns.stream().mapToLong(RunResult::scoreMismatchFailures).sum(),
                    baselineRuns.stream().filter(RunResult::executionFailed).count(),
                    candidateRuns.stream().filter(RunResult::executionFailed).count(),
                    profile == Profile.WALL_CLOCK
                            ? pairs.stream().flatMap(pair -> pair.runs().stream())
                                    .filter(run -> run.elapsedMs() > wallClockLimitMs).count()
                            : 0L);
        }

        private static String scoreQuantile(List<RosterScore> scores, double quantile) {
            return scores.isEmpty()
                    ? "N/A"
                    : Phase6BenchmarkStatistics.nearestRankScore(scores, quantile).toString();
        }

        private static long[] deltaQuantile(List<ScorePair> pairs, double quantile) {
            return pairs.isEmpty()
                    ? new long[1 + RosterScore.SOFT_LEVELS]
                    : Phase6BenchmarkStatistics.pairedDeltaQuantile(pairs, quantile);
        }

        private static long longQuantile(List<Long> values, double quantile) {
            return values.isEmpty()
                    ? 0L
                    : Phase6BenchmarkStatistics.nearestRankLong(values, quantile);
        }
    }

    private record OperatorAggregate(
            String operatorId,
            long selectionCount,
            long globalBestCount,
            long currentImprovementCount,
            long acceptedWorseningCount,
            long rejectionCount,
            long finalBestContributionRuns) {
    }

    private static final class MutableOperatorAggregate {
        private final String operatorId;
        private long selectionCount;
        private long globalBestCount;
        private long currentImprovementCount;
        private long acceptedWorseningCount;
        private long rejectionCount;
        private long finalBestContributionRuns;

        private MutableOperatorAggregate(String operatorId) {
            this.operatorId = operatorId;
        }

        private void add(OperatorStatistics statistics) {
            selectionCount += statistics.selectionCount();
            globalBestCount += statistics.globalBestCount();
            currentImprovementCount += statistics.currentImprovementCount();
            acceptedWorseningCount += statistics.acceptedWorseningCount();
            rejectionCount += statistics.rejectionCount();
        }

        private OperatorAggregate snapshot() {
            return new OperatorAggregate(
                    operatorId,
                    selectionCount,
                    globalBestCount,
                    currentImprovementCount,
                    acceptedWorseningCount,
                    rejectionCount,
                    finalBestContributionRuns);
        }
    }

    private static void putScore(ObjectNode json, RosterScore score) {
        if (score == null) {
            json.putNull("score");
            json.putNull("hard_score");
            json.putNull("soft_scores");
            json.put("feasible", false);
            return;
        }
        json.put("score", score.toString());
        json.put("hard_score", score.hardScore());
        ArrayNode softScores = json.putArray("soft_scores");
        for (int level = 0; level < RosterScore.SOFT_LEVELS; level++) {
            softScores.add(score.softScore(level));
        }
        json.put("feasible", score.isFeasible());
    }
}
