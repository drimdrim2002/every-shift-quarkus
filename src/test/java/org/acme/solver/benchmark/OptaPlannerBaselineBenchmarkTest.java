package org.acme.solver.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.EmployeeScheduleBuilder;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.acme.solver.algorithm.EmployeeSchedulingConstraintProvider;
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

@Tag("benchmark")
class OptaPlannerBaselineBenchmarkTest {

    private static final int SCHEMA_VERSION = 1;
    private static final List<String> DEFAULT_DATASETS = List.of(
            "fairness.json", "preceptor.json", "request.json", "sample.json");
    private static final List<Long> DEFAULT_SEEDS = List.of(42L, 43L, 44L, 45L, 46L, 47L, 48L, 49L, 50L, 51L);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final EmployeeScheduleBuilder scheduleBuilder = new EmployeeScheduleBuilder();

    @Test
    void captureBaseline() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("benchmark.enabled"),
                "Phase 0 benchmark는 -Dbenchmark.enabled=true일 때만 실행합니다.");

        BenchmarkConfig config = BenchmarkConfig.fromSystemProperties();
        Files.createDirectories(config.output().toAbsolutePath().getParent());
        Files.writeString(config.output(), "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        append(config.output(), metadataRecord(config));

        if (config.warmup()) {
            for (Profile profile : config.profiles()) {
                runOnce("fairness_test.json", config.seeds().getFirst(), 0, profile, config, true);
            }
        }

        List<RunResult> results = new ArrayList<>();
        for (String dataset : config.datasets()) {
            for (int seedIndex = 0; seedIndex < config.seeds().size(); seedIndex++) {
                long seed = config.seeds().get(seedIndex);
                for (int repeat = 1; repeat <= config.repeats(); repeat++) {
                    List<Profile> orderedProfiles = rotatedProfiles(config.profiles(), seedIndex + repeat - 1);
                    for (Profile profile : orderedProfiles) {
                        RunResult result = runOnce(dataset, seed, repeat, profile, config, false);
                        results.add(result);
                        append(config.output(), result.toJson(objectMapper, config));
                    }
                }
            }
        }

        appendRepeatSummaries(config.output(), results, config);
        assertEquals(config.datasets().size() * config.seeds().size() * config.repeats()
                * config.profiles().size(), results.size());
    }

    private RunResult runOnce(String dataset, long seed, int repeat, Profile profile,
            BenchmarkConfig config, boolean warmup) throws Exception {
        DatasetInput input = loadDataset(dataset);
        EmployeeSchedule problem = scheduleBuilder.build(input.request());
        Solver<EmployeeSchedule> solver = createSolver(profile, seed, config, warmup);

        AtomicLong bestReachedMs = new AtomicLong();
        AtomicLong bestChangeCount = new AtomicLong();
        solver.addEventListener(event -> {
            bestReachedMs.set(event.getTimeMillisSpent());
            bestChangeCount.incrementAndGet();
        });

        resetHeapPeakUsage();
        long heapUsedBefore = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        long startedNanos = System.nanoTime();
        EmployeeSchedule solution = solver.solve(problem);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        long heapUsedAfter = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        long heapPeakUsed = heapPeakUsedBytes();

        BendableScore score = solution.getScore();
        assertNotNull(score, dataset + " 결과 점수");
        assertEquals(1, score.hardLevelsSize(), dataset + " hard level 수");
        assertEquals(4, score.softLevelsSize(), dataset + " soft level 수");

        long scoreCalculationCount = ((DefaultSolver<EmployeeSchedule>) solver)
                .getSolverScope().getScoreCalculationCount();
        Long solverBestReachedMs = ((DefaultSolver<EmployeeSchedule>) solver)
                .getSolverScope().getBestSolutionTimeMillisSpent();

        return new RunResult(
                dataset,
                input.sha256(),
                seed,
                repeat,
                profile,
                score,
                elapsedMs,
                solverBestReachedMs != null ? solverBestReachedMs : bestReachedMs.get(),
                bestChangeCount.get(),
                scoreCalculationCount,
                heapUsedBefore,
                heapUsedAfter,
                heapPeakUsed,
                assignmentFingerprint(solution));
    }

    private Solver<EmployeeSchedule> createSolver(Profile profile, long seed, BenchmarkConfig config,
            boolean warmup) {
        TerminationConfig termination = switch (profile) {
            case WALL_CLOCK -> new TerminationConfig().withSpentLimit(
                    warmup ? Duration.ofSeconds(Math.min(1L, config.wallClockSeconds()))
                            : Duration.ofSeconds(config.wallClockSeconds()));
            case FIXED_EVALUATIONS -> new TerminationConfig().withScoreCalculationCountLimit(
                    warmup ? Math.min(10_000L, config.evaluationLimit()) : config.evaluationLimit());
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
        return new DatasetInput(objectMapper.readValue(bytes, PlanningRequest.class), sha256(bytes));
    }

    private ObjectNode metadataRecord(BenchmarkConfig config) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("record_type", "metadata");
        json.put("schema_version", SCHEMA_VERSION);
        json.put("captured_at", Instant.now().toString());
        json.put("environment", config.environment());
        json.put("run_label", config.runLabel());
        json.put("os_name", System.getProperty("os.name"));
        json.put("os_version", System.getProperty("os.version"));
        json.put("os_arch", System.getProperty("os.arch"));
        json.put("java_version", System.getProperty("java.version"));
        json.put("java_vendor", System.getProperty("java.vendor"));
        json.put("available_processors", Runtime.getRuntime().availableProcessors());
        json.put("max_heap_bytes", Runtime.getRuntime().maxMemory());
        json.put("git_commit", gitOutput("rev-parse", "HEAD"));
        json.put("git_dirty", !gitOutput("status", "--porcelain", "--untracked-files=no").isBlank());
        json.put("solver_engine", "OPTAPLANNER");
        json.put("solver_environment_mode", "REPRODUCIBLE");
        json.put("move_thread_count", "NONE");
        json.put("warmup_excluded", config.warmup());
        json.set("datasets", objectMapper.valueToTree(config.datasets()));
        json.set("seeds", objectMapper.valueToTree(config.seeds()));
        json.set("profiles", objectMapper.valueToTree(config.profiles().stream().map(Profile::id).toList()));
        json.put("repeats", config.repeats());
        json.put("wall_clock_seconds", config.wallClockSeconds());
        json.put("evaluation_limit", config.evaluationLimit());
        json.put("config_fingerprint_sha256", sha256(String.join("|",
                config.environment(), config.runLabel(), config.datasets().toString(), config.seeds().toString(),
                config.profiles().toString(), Integer.toString(config.repeats()),
                Long.toString(config.wallClockSeconds()), Long.toString(config.evaluationLimit()),
                "REPRODUCIBLE", "NONE").getBytes(StandardCharsets.UTF_8)));
        return json;
    }

    private void appendRepeatSummaries(Path output, List<RunResult> results, BenchmarkConfig config) throws Exception {
        Map<String, List<RunResult>> groups = results.stream().collect(Collectors.groupingBy(
                result -> result.dataset() + "|" + result.seed() + "|" + result.profile().id(),
                LinkedHashMap::new,
                Collectors.toList()));

        for (List<RunResult> group : groups.values()) {
            RunResult first = group.getFirst();
            ObjectNode json = objectMapper.createObjectNode();
            json.put("record_type", "repeat_summary");
            json.put("schema_version", SCHEMA_VERSION);
            json.put("environment", config.environment());
            json.put("dataset", first.dataset());
            json.put("seed", first.seed());
            json.put("profile", first.profile().id());
            json.put("repeat_count", group.size());
            if (group.size() > 1) {
                json.put("score_vector_stable", group.stream()
                        .map(result -> result.score().toString()).distinct().count() == 1);
                json.put("assignment_fingerprint_stable", group.stream()
                        .map(RunResult::assignmentFingerprint).distinct().count() == 1);
            } else {
                json.putNull("score_vector_stable");
                json.putNull("assignment_fingerprint_stable");
            }
            json.put("elapsed_ms_min", group.stream().mapToLong(RunResult::elapsedMs).min().orElseThrow());
            json.put("elapsed_ms_max", group.stream().mapToLong(RunResult::elapsedMs).max().orElseThrow());
            json.put("best_reached_ms_min", group.stream().mapToLong(RunResult::bestReachedMs).min().orElseThrow());
            json.put("best_reached_ms_max", group.stream().mapToLong(RunResult::bestReachedMs).max().orElseThrow());
            append(output, json);
        }
    }

    private static List<Profile> rotatedProfiles(List<Profile> profiles, int offset) {
        if (profiles.size() < 2 || offset % profiles.size() == 0) {
            return profiles;
        }
        List<Profile> rotated = new ArrayList<>(profiles.size());
        for (int index = 0; index < profiles.size(); index++) {
            rotated.add(profiles.get((index + offset) % profiles.size()));
        }
        return rotated;
    }

    private void append(Path output, ObjectNode json) throws Exception {
        Files.writeString(output, objectMapper.writeValueAsString(json) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String assignmentFingerprint(EmployeeSchedule solution) {
        String canonicalAssignments = solution.getShiftList().stream()
                .sorted(Comparator.comparing(Shift::getId, Comparator.nullsFirst(Long::compareTo)))
                .map(shift -> String.valueOf(shift.getId()) + "="
                        + (shift.getEmployee() == null ? "<unassigned>" : shift.getEmployee().getId()))
                .collect(Collectors.joining("\n"));
        return sha256(canonicalAssignments.getBytes(StandardCharsets.UTF_8));
    }

    private static void resetHeapPeakUsage() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
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
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 ? output : "unknown";
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256을 계산할 수 없습니다.", exception);
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
                default -> throw new IllegalArgumentException("알 수 없는 benchmark profile: " + value);
            };
        }
    }

    private record DatasetInput(PlanningRequest request, String sha256) {
    }

    private record BenchmarkConfig(
            List<String> datasets,
            List<Long> seeds,
            int repeats,
            List<Profile> profiles,
            long wallClockSeconds,
            long evaluationLimit,
            boolean warmup,
            String environment,
            String runLabel,
            Path output) {

        static BenchmarkConfig fromSystemProperties() {
            List<String> datasets = csvProperty("benchmark.datasets", DEFAULT_DATASETS);
            List<Long> seeds = csvProperty("benchmark.seeds", DEFAULT_SEEDS.stream().map(String::valueOf).toList())
                    .stream().map(Long::parseLong).toList();
            List<Profile> profiles = csvProperty("benchmark.profiles",
                    List.of("wall-clock", "fixed-evaluations")).stream().map(Profile::parse).toList();
            int repeats = Integer.getInteger("benchmark.repeats", 2);
            long wallClockSeconds = Long.getLong("benchmark.wall-clock-seconds", 60L);
            long evaluationLimit = Long.getLong("benchmark.evaluation-limit", 100_000L);
            boolean warmup = Boolean.parseBoolean(System.getProperty("benchmark.warmup", "true"));
            String environment = System.getProperty("benchmark.environment", "macos-local").strip();
            String runLabel = System.getProperty("benchmark.run-label", "phase0-baseline").strip();
            Path output = Path.of(System.getProperty("benchmark.output",
                    "target/benchmarks/phase0/" + environment + "/baseline.jsonl"));

            if (datasets.isEmpty() || seeds.isEmpty() || profiles.isEmpty()) {
                throw new IllegalArgumentException("dataset, seed, profile은 각각 하나 이상이어야 합니다.");
            }
            if (repeats < 1 || wallClockSeconds < 1 || evaluationLimit < 1) {
                throw new IllegalArgumentException("repeats와 benchmark 예산은 1 이상이어야 합니다.");
            }
            if (!environment.equals("macos-local") && !environment.equals("container")) {
                throw new IllegalArgumentException("benchmark.environment는 macos-local 또는 container여야 합니다.");
            }
            return new BenchmarkConfig(datasets, seeds, repeats, profiles, wallClockSeconds,
                    evaluationLimit, warmup, environment, runLabel, output);
        }

        private static <T> List<String> csvProperty(String name, List<T> defaults) {
            String defaultValue = defaults.stream().map(String::valueOf).collect(Collectors.joining(","));
            return Arrays.stream(System.getProperty(name, defaultValue).split(","))
                    .map(String::strip)
                    .filter(value -> !value.isEmpty())
                    .toList();
        }
    }

    private record RunResult(
            String dataset,
            String inputSha256,
            long seed,
            int repeat,
            Profile profile,
            BendableScore score,
            long elapsedMs,
            long bestReachedMs,
            long bestChangeCount,
            long scoreCalculationCount,
            long heapUsedBefore,
            long heapUsedAfter,
            long heapPeakUsed,
            String assignmentFingerprint) {

        ObjectNode toJson(ObjectMapper objectMapper, BenchmarkConfig config) {
            ObjectNode json = objectMapper.createObjectNode();
            json.put("record_type", "run");
            json.put("schema_version", SCHEMA_VERSION);
            json.put("captured_at", Instant.now().toString());
            json.put("environment", config.environment());
            json.put("run_label", config.runLabel());
            json.put("engine", "OPTAPLANNER");
            json.put("dataset", dataset);
            json.put("input_sha256", inputSha256);
            json.put("seed", seed);
            json.put("repeat", repeat);
            json.put("profile", profile.id());
            if (profile == Profile.WALL_CLOCK) {
                json.put("wall_clock_limit_ms", Duration.ofSeconds(config.wallClockSeconds()).toMillis());
                json.putNull("evaluation_limit");
            } else {
                json.putNull("wall_clock_limit_ms");
                json.put("evaluation_limit", config.evaluationLimit());
            }
            json.put("score", score.toString());
            json.put("hard_score", score.hardScore(0));
            ArrayNode softScores = json.putArray("soft_scores");
            for (int index = 0; index < score.softLevelsSize(); index++) {
                softScores.add(score.softScore(index));
            }
            json.put("feasible", score.isFeasible());
            json.put("elapsed_ms", elapsedMs);
            json.put("best_reached_ms", bestReachedMs);
            json.put("best_solution_change_count", bestChangeCount);
            json.put("score_calculation_count", scoreCalculationCount);
            json.put("heap_used_before_bytes", heapUsedBefore);
            json.put("heap_used_after_bytes", heapUsedAfter);
            json.put("heap_used_delta_bytes", heapUsedAfter - heapUsedBefore);
            json.put("heap_peak_used_bytes", heapPeakUsed);
            json.put("assignment_fingerprint_sha256", assignmentFingerprint);
            return json;
        }
    }
}
