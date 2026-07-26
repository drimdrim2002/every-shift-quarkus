package org.acme.solver.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.EmployeeScheduleBuilder;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.acme.solver.adapter.PlanningProblemMapper;
import org.acme.solver.algorithm.EmployeeSchedulingConstraintProvider;
import org.acme.solver.alns.AlnsRunMetrics;
import org.acme.solver.alns.AlnsSolverEngine;
import org.acme.solver.alns.OperatorStatistics;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveMetrics;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.initial.InitialSolutionBuilder;
import org.acme.solver.initial.InitialSolutionResult;
import org.acme.solver.lahc.AlnsChangeSwapVndHybridMetrics;
import org.acme.solver.lahc.AlnsChangeSwapVndHybridSolverEngine;
import org.acme.solver.lahc.FairnessRestrictedLocalSearchMetrics;
import org.acme.solver.lahc.OrderedVndLocalSearchMetrics;
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
 * ALNS only / ALNS→Change/Swap / ALNS→ordered VND(+protected fairness)를 한 Opta cache와
 * paired 비교하는 장기 test-only benchmark입니다. production CDI와 설정에는 연결하지 않습니다.
 */
@Tag("benchmark")
class Phase6HybridVndLongBenchmarkTest {

    private static final List<String> DEFAULT_DATASETS = List.of(
            "fairness.json", "preceptor.json", "request.json", "sample.json");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final EmployeeScheduleBuilder scheduleBuilder = new EmployeeScheduleBuilder();
    private final PlanningProblemMapper problemMapper = new PlanningProblemMapper();

    @Test
    void captureHybridVndLongBenchmark() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("phase6.hybrid-vnd.enabled"),
                "hybrid VND long benchmark는 전용 script로만 실행합니다.");
        Config config = Config.fromProperties();
        Files.createDirectories(config.output().toAbsolutePath().getParent());
        Files.writeString(config.output(), "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        append(metadata(config));

        if (config.warmup()) {
            Dataset warmup = load("fairness_test.json");
            runOpta(warmup, config.seeds().getFirst(), config, true);
            runCandidate(Candidate.ALNS_ONLY, warmup, config.seeds().getFirst(), config, true);
            runCandidate(Candidate.ALNS_THEN_CHANGE_SWAP, warmup, config.seeds().getFirst(), config, true);
            runCandidate(Candidate.ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS, warmup,
                    config.seeds().getFirst(), config, true);
        }

        List<Pair> pairs = new ArrayList<>();
        int ordinal = 0;
        for (int datasetIndex = 0; datasetIndex < config.datasets().size(); datasetIndex++) {
            Dataset dataset = load(config.datasets().get(datasetIndex));
            for (int seedIndex = 0; seedIndex < config.seeds().size(); seedIndex++) {
                long seed = config.seeds().get(seedIndex);
                List<Candidate> candidateOrder = rotatedCandidates(datasetIndex + seedIndex, config.candidates());
                boolean optaFirst = Math.floorMod(datasetIndex + seedIndex, 2) == 0;
                Run opta = null;
                if (optaFirst) {
                    opta = runOpta(dataset, seed, config, false);
                    append(opta.toJson(mapper));
                }
                for (Candidate candidate : candidateOrder) {
                    Run result = runCandidate(candidate, dataset, seed, config, false);
                    append(result.toJson(mapper));
                    if (opta == null) {
                        opta = runOpta(dataset, seed, config, false);
                        append(opta.toJson(mapper));
                    }
                    Pair pair = new Pair(dataset.name(), seed, ordinal++, optaFirst, opta, result);
                    pairs.add(pair);
                    append(pair.toJson(mapper));
                }
            }
        }
        writeSummaryAndReport(config, pairs);
        assertEquals(config.datasets().size() * config.seeds().size() * config.candidates().size(), pairs.size());
        assertEquals(0L, pairs.stream().flatMap(pair -> List.of(pair.opta(), pair.candidate()).stream())
                .filter(Run::executionFailed).count(), "실행 실패가 있으면 artifact를 검토해야 합니다.");
    }

    private Run runOpta(Dataset dataset, long seed, Config config, boolean warmup) {
        long started = System.nanoTime();
        try {
            EmployeeSchedule input = scheduleBuilder.build(dataset.request());
            Solver<EmployeeSchedule> solver = SolverFactory.<EmployeeSchedule>create(optaConfig(seed, config, warmup)).buildSolver();
            DefaultSolver<EmployeeSchedule> defaultSolver = (DefaultSolver<EmployeeSchedule>) solver;
            AtomicLong bestEvaluation = new AtomicLong();
            AtomicLong bestMillis = new AtomicLong();
            solver.addEventListener(event -> {
                bestEvaluation.set(defaultSolver.getSolverScope().getScoreCalculationCount());
                bestMillis.set(event.getTimeMillisSpent());
            });
            EmployeeSchedule solution = solver.solve(input);
            BendableScore score = Objects.requireNonNull(solution.getScore(), "Opta score");
            boolean complete = solution.getShiftList().stream().noneMatch(shift -> shift.getEmployee() == null);
            if (!complete) {
                throw new IllegalStateException("OptaPlanner가 incomplete solution을 반환했습니다.");
            }
            return Run.opta(dataset, seed, elapsed(started), OptaPlannerScoreAdapter.toRosterScore(score),
                    defaultSolver.getSolverScope().getScoreCalculationCount(), bestEvaluation.get(), bestMillis.get());
        } catch (RuntimeException failure) {
            return Run.failure("OPTAPLANNER", dataset, seed, elapsed(started), failure);
        }
    }

    private Run runCandidate(Candidate candidate, Dataset dataset, long seed, Config config, boolean warmup) {
        long started = System.nanoTime();
        try {
            PlanningProblem problem = problemMapper.toPlanningProblem(scheduleBuilder.build(dataset.request()));
            FullScoreCalculator full = new FullScoreCalculator();
            InitialSolutionResult initialResult = new InitialSolutionBuilder(full).build(problem);
            if (!initialResult.succeeded()) {
                throw new IllegalStateException("초기 complete 해 생성 실패");
            }
            RosterSolution initial = initialResult.solution();
            SolveOptions options = options(seed, initial, config, warmup);
            AtomicLong bestEvaluation = new AtomicLong();
            AtomicLong bestMillis = new AtomicLong();
            SolveResult<RosterSolution> result;
            if (candidate == Candidate.ALNS_ONLY) {
                result = new AlnsSolverEngine().solve(problem, options, ignored -> {
                    bestMillis.set(elapsed(started));
                });
            } else {
                AlnsChangeSwapVndHybridSolverEngine.Mode mode = candidate == Candidate.ALNS_THEN_CHANGE_SWAP
                        ? AlnsChangeSwapVndHybridSolverEngine.Mode.ALNS_THEN_CHANGE_SWAP
                        : AlnsChangeSwapVndHybridSolverEngine.Mode.ALNS_THEN_ORDERED_VND_WITH_PROTECTED_FAIRNESS;
                result = new AlnsChangeSwapVndHybridSolverEngine(mode).solve(problem, options, ignored -> {
                    bestMillis.set(elapsed(started));
                });
            }
            RosterSolution best = Objects.requireNonNull(result.bestSolution(), "candidate best");
            RosterScore independentlyVerified = full.calculateScore(problem, best);
            if (!independentlyVerified.equals(result.score())) {
                throw new IllegalStateException("candidate 최종 full score가 result score와 다릅니다.");
            }
            bestEvaluation.set(bestEvaluation(result.metrics(), result.evaluationCount()));
            return Run.candidate(candidate, dataset, seed, elapsed(started), result, bestEvaluation.get(),
                    bestMillis.get(), initial.score());
        } catch (RuntimeException failure) {
            return Run.failure(candidate.name(), dataset, seed, elapsed(started), failure);
        }
    }

    private SolveOptions options(long seed, RosterSolution initial, Config config, boolean warmup) {
        if (config.profile() == Profile.FIXED) {
            return SolveOptions.builder().warmStart(initial).randomSeed(seed)
                    .maxEvaluations(warmup ? Math.min(64L, config.pojoEvaluations()) : config.pojoEvaluations()).build();
        }
        long seconds = warmup ? Math.min(1L, config.wallSeconds()) : config.wallSeconds();
        return SolveOptions.builder().warmStart(initial).randomSeed(seed).spentLimit(Duration.ofSeconds(seconds)).build();
    }

    private SolverConfig optaConfig(long seed, Config config, boolean warmup) {
        TerminationConfig termination = config.profile() == Profile.FIXED
                ? new TerminationConfig().withScoreCalculationCountLimit(warmup
                        ? Math.min(1_000L, config.optaEvaluations()) : config.optaEvaluations())
                : new TerminationConfig().withSpentLimit(Duration.ofSeconds(warmup ? Math.min(1L, config.wallSeconds()) : config.wallSeconds()));
        return new SolverConfig().withSolutionClass(EmployeeSchedule.class).withEntityClasses(Shift.class)
                .withConstraintProviderClass(EmployeeSchedulingConstraintProvider.class).withTerminationConfig(termination)
                .withMoveThreadCount("NONE").withEnvironmentMode(EnvironmentMode.REPRODUCIBLE).withRandomSeed(seed);
    }

    private ObjectNode metadata(Config config) {
        ObjectNode node = mapper.createObjectNode();
        node.put("record_type", "metadata");
        node.put("schema_version", 1);
        node.put("captured_at", Instant.now().toString());
        node.put("candidate_family", "POJO_ALNS_CHANGE_SWAP_ORDERED_VND_TEST_ONLY");
        node.put("profile", config.profile().id());
        node.put("partition", config.partition());
        node.set("datasets", mapper.valueToTree(config.datasets()));
        node.set("seeds", mapper.valueToTree(config.seeds()));
        node.set("candidates", mapper.valueToTree(config.candidates()));
        node.put("warmup_executed_and_excluded", config.warmup());
        node.put("crossed_order", "case parity로 Opta-first/candidate-first, candidate 순서도 순환");
        node.put("opta_cache_scope", "input_sha256/seed/profile/opta_budget; 같은 case에서 후보 3개가 Opta 1회 결과를 공유");
        node.put("lexicographic_order", "hard > soft[0] > soft[1] > soft[2] > soft[3]");
        node.put("fixed_unit_note", "OptaPlanner=score-calculation, POJO=complete candidate evaluation; throughput 직접 비교 금지");
        node.put("stage_budget_contract", "ALNS 80%; Change/Swap은 20%, protected mode는 VND 15% + fairness 5%; 조기 종료 잔여 예산은 다음 단계로 이월");
        return node;
    }

    private void writeSummaryAndReport(Config config, List<Pair> pairs) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("schema_version", 1);
        root.put("profile", config.profile().id());
        root.put("partition", config.partition());
        root.put("config_fingerprint_sha256", config.fingerprint());
        root.put("opta_cache_scope", "input_sha256/seed/profile/opta_budget");
        ArrayNode groups = root.putArray("groups");
        List<String> names = new ArrayList<>(); names.add("ALL"); names.addAll(config.datasets());
        for (Candidate candidate : config.candidates()) {
            for (String name : names) {
                groups.add(group(name, candidate, select(pairs, name, candidate)));
            }
        }
        root.set("candidate_distribution", mapper.valueToTree(distribution(pairs)));
        Files.writeString(config.summary(), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root)
                + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        StringBuilder report = new StringBuilder("# Phase 6 POJO ALNS → Change/Swap → ordered VND 장기 benchmark\n\n")
                .append("- 상태: test-only 후보. production 기본값·점수 의미·Phase 0 gate는 변경하지 않았습니다.\n")
                .append("- profile: `").append(config.profile().id()).append("`, partition/seed: `")
                .append(config.partition()).append("` / `").append(config.seeds()).append("`\n")
                .append("- warm-up: ").append(config.warmup() ? "실행 후 집계 제외" : "미실행").append("\n")
                .append("- Opta cache scope: `input_sha256/seed/profile/opta_budget`; 각 case의 Opta 결과 1개를 세 후보가 공유합니다.\n")
                .append("- 비교: 엄격한 `hard > soft[0] > soft[1] > soft[2] > soft[3]`; W/T/L은 최초 차이 목적식으로 결정합니다.\n")
                .append("- fixed 단위: Opta score calculation과 POJO candidate evaluation은 서로 달라 처리량 비교가 아닙니다.\n\n")
                .append("## 품질·안전성\n\n")
                .append("| 후보 | dataset | pairs | feasible Opta/POJO | W/T/L | POJO score p10/median/p90 | paired delta p10/median/p90 | soft2 delta p10/median/p90 | best eval p50/p90 | elapsed p95 Opta/POJO | rollback/mismatch/corruption |\n")
                .append("|---|---|---:|---:|---:|---|---|---|---:|---:|---:|\n");
        for (Candidate candidate : config.candidates()) {
            for (String name : names) {
                ObjectNode group = group(name, candidate, select(pairs, name, candidate));
                report.append("| ").append(candidate.name()).append(" | ").append(name)
                        .append(" | ").append(group.path("pair_count").asLong())
                        .append(" | ").append(group.path("opta_feasible").asLong()).append('/').append(group.path("candidate_feasible").asLong())
                        .append(" | ").append(group.path("wins").asLong()).append('/').append(group.path("ties").asLong()).append('/').append(group.path("losses").asLong())
                        .append(" | ").append(group.path("candidate_score_p10").asText()).append(" / ").append(group.path("candidate_score_median").asText()).append(" / ").append(group.path("candidate_score_p90").asText())
                        .append(" | ").append(group.path("delta_p10")).append(" / ").append(group.path("delta_median")).append(" / ").append(group.path("delta_p90"))
                        .append(" | ").append(group.path("soft2_p10").asLong()).append(" / ").append(group.path("soft2_median").asLong()).append(" / ").append(group.path("soft2_p90").asLong())
                        .append(" | ").append(group.path("best_eval_p50").asLong()).append('/').append(group.path("best_eval_p90").asLong())
                        .append(" | ").append(group.path("opta_elapsed_p95").asLong()).append('/').append(group.path("candidate_elapsed_p95").asLong())
                        .append(" | ").append(group.path("rollback_attempts").asLong()).append('/').append(group.path("score_mismatches").asLong()).append('/').append(group.path("state_corruptions").asLong()).append(" |\n");
            }
        }
        report.append("\n## 최초 차이 목적식·prefix equality\n\n");
        for (Candidate candidate : config.candidates()) {
            report.append("- `").append(candidate.name()).append("`: ")
                    .append(group("ALL", candidate, select(pairs, "ALL", candidate)).path("decisive_levels")).append("\n");
        }
        report.append("\n## 단계·선택 분포\n\n`raw.jsonl`의 candidate record에 ALNS destroy/repair, ordered VND Reassign/Swap, protected fairness selector, 각 stage 입력/출력 score·예약/사용/unused budget·termination을 분리 기록합니다. `summary.json`의 `candidate_distribution`은 이를 전체 집계합니다.\n\n")
                .append("## 해석 경계\n\n이 artifact는 Phase 0 수치 gate를 만들거나 promotion/default 활성화를 선언하지 않습니다. 120초 profile은 60초 Opta cache와 비교할 경우 extra-compute comparison으로만 해석해야 하며 equal-cost 승리라고 표현하지 않습니다.\n");
        Files.writeString(config.report(), report.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private ObjectNode group(String name, Candidate candidate, List<Pair> pairs) {
        ObjectNode node = mapper.createObjectNode();
        node.put("candidate", candidate.name()); node.put("dataset", name); node.put("pair_count", pairs.size());
        List<Pair> comparable = pairs.stream().filter(Pair::comparable).toList();
        long wins = comparable.stream().filter(pair -> pair.candidate().score().compareTo(pair.opta().score()) > 0).count();
        long ties = comparable.stream().filter(pair -> pair.candidate().score().compareTo(pair.opta().score()) == 0).count();
        node.put("opta_feasible", pairs.stream().filter(pair -> pair.opta().feasible()).count());
        node.put("candidate_feasible", pairs.stream().filter(pair -> pair.candidate().feasible()).count());
        node.put("wins", wins); node.put("ties", ties); node.put("losses", comparable.size() - wins - ties);
        putScore(node, "candidate_score", comparable.stream().map(pair -> pair.candidate().score()).toList());
        putDelta(node, comparable);
        putLong(node, "soft2", comparable.stream().map(pair -> pair.candidate().score().softDeltaFrom(pair.opta().score(), 2)).toList());
        putLong(node, "best_eval", pairs.stream().map(pair -> pair.candidate().bestEvaluation()).toList());
        putLong(node, "opta_elapsed", pairs.stream().map(pair -> pair.opta().elapsedMillis()).toList());
        putLong(node, "candidate_elapsed", pairs.stream().map(pair -> pair.candidate().elapsedMillis()).toList());
        node.put("rollback_attempts", pairs.stream().mapToLong(pair -> pair.candidate().rollbackAttempts()).sum());
        node.put("score_mismatches", pairs.stream().mapToLong(pair -> pair.candidate().mismatches()).sum());
        node.put("state_corruptions", pairs.stream().mapToLong(pair -> pair.candidate().corruptions()).sum());
        node.set("decisive_levels", mapper.valueToTree(decisive(comparable)));
        return node;
    }

    private Map<String, Object> distribution(List<Pair> pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Candidate candidate : Candidate.values()) {
            List<Run> runs = pairs.stream().filter(pair -> pair.candidate().candidate() == candidate).map(Pair::candidate).toList();
            long alnsSelections = 0L, alnsFinal = 0L, vndReassign = 0L, vndSwap = 0L, vndAccepted = 0L;
            long fairnessAccepted = 0L, fairnessFinal = 0L;
            for (Run run : runs) {
                for (SolveMetrics metrics : run.stageMetrics()) {
                    if (metrics instanceof AlnsRunMetrics alns) {
                        alnsSelections += alns.destroyOperators().stream().mapToLong(OperatorStatistics::selectionCount).sum();
                        if (!alns.bestImprovements().isEmpty()) alnsFinal++;
                    } else if (metrics instanceof OrderedVndLocalSearchMetrics vnd) {
                        vndReassign += vnd.evaluatedReassignCandidates(); vndSwap += vnd.evaluatedSwapCandidates();
                        vndAccepted += vnd.acceptedReassignCandidates() + vnd.acceptedSwapCandidates();
                    } else if (metrics instanceof FairnessRestrictedLocalSearchMetrics fairness) {
                        fairnessAccepted += fairness.acceptedCandidates();
                        if (!fairness.bestImprovements().isEmpty()) fairnessFinal++;
                    }
                }
            }
            result.put(candidate.name(), Map.of("alns_destroy_selections", alnsSelections,
                    "alns_final_best_runs", alnsFinal, "vnd_evaluated_reassign", vndReassign,
                    "vnd_evaluated_swap", vndSwap, "vnd_accepted", vndAccepted,
                    "fairness_accepted", fairnessAccepted, "fairness_final_best_runs", fairnessFinal));
        }
        return result;
    }

    private static List<Pair> select(List<Pair> pairs, String name, Candidate candidate) {
        return pairs.stream().filter(pair -> pair.candidate().candidate() == candidate)
                .filter(pair -> "ALL".equals(name) || pair.dataset().equals(name)).toList();
    }

    private static List<Candidate> rotatedCandidates(int offset, List<Candidate> candidates) {
        List<Candidate> rotated = new ArrayList<>(candidates);
        java.util.Collections.rotate(rotated, Math.floorMod(offset, rotated.size()));
        return rotated;
    }

    private static Map<String, long[]> decisive(List<Pair> pairs) {
        String[] names = { "hard", "soft0", "soft1", "soft2", "soft3", "tie" };
        Map<String, long[]> totals = new LinkedHashMap<>();
        for (String name : names) totals.put(name, new long[3]);
        for (Pair pair : pairs) {
            int level = decisiveLevel(pair.candidate().score(), pair.opta().score());
            long[] count = totals.get(names[level + 1]);
            int comparison = pair.candidate().score().compareTo(pair.opta().score());
            count[comparison > 0 ? 0 : comparison < 0 ? 2 : 1]++;
        }
        return totals;
    }

    private static int decisiveLevel(RosterScore candidate, RosterScore opta) {
        if (candidate.hardScore() != opta.hardScore()) return -1;
        for (int level = 0; level < RosterScore.SOFT_LEVELS; level++) {
            if (candidate.softScore(level) != opta.softScore(level)) return level;
        }
        return 4;
    }

    private void putScore(ObjectNode node, String prefix, List<RosterScore> scores) {
        node.put(prefix + "_p10", scoreQuantile(scores, .10));
        node.put(prefix + "_median", scoreQuantile(scores, .50));
        node.put(prefix + "_p90", scoreQuantile(scores, .90));
    }

    private void putDelta(ObjectNode node, List<Pair> pairs) {
        putVector(node, "delta_p10", pairs, .10); putVector(node, "delta_median", pairs, .50); putVector(node, "delta_p90", pairs, .90);
    }

    private void putVector(ObjectNode node, String field, List<Pair> pairs, double q) {
        ArrayNode values = node.putArray(field);
        for (int level = -1; level < RosterScore.SOFT_LEVELS; level++) {
            int coordinate = level;
            List<Long> numbers = pairs.stream().map(pair -> coordinate < 0
                    ? pair.candidate().score().hardDeltaFrom(pair.opta().score())
                    : pair.candidate().score().softDeltaFrom(pair.opta().score(), coordinate)).toList();
            values.add(longQuantile(numbers, q));
        }
    }

    private void putLong(ObjectNode node, String prefix, List<Long> values) {
        node.put(prefix + "_p10", longQuantile(values, .10));
        node.put(prefix + "_median", longQuantile(values, .50));
        node.put(prefix + "_p90", longQuantile(values, .90));
        node.put(prefix + "_p50", longQuantile(values, .50));
        if ("candidate_elapsed".equals(prefix) || "opta_elapsed".equals(prefix)) node.put(prefix + "_p95", longQuantile(values, .95));
    }

    private static String scoreQuantile(List<RosterScore> values, double quantile) {
        if (values.isEmpty()) return "N/A";
        List<RosterScore> sorted = values.stream().sorted().toList();
        return sorted.get(quantileIndex(sorted.size(), quantile)).toString();
    }

    private static long longQuantile(List<Long> values, double quantile) {
        if (values.isEmpty()) return 0L;
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get(quantileIndex(sorted.size(), quantile));
    }

    private static int quantileIndex(int size, double quantile) {
        return Math.max(0, (int) Math.ceil(size * quantile) - 1);
    }

    private Dataset load(String name) throws Exception {
        byte[] bytes;
        try (InputStream input = getClass().getResourceAsStream("/json/" + name)) {
            if (input == null) throw new IllegalArgumentException("입력을 찾을 수 없습니다: " + name);
            bytes = input.readAllBytes();
        }
        return new Dataset(name, mapper.readValue(bytes, PlanningRequest.class), sha256(bytes));
    }

    private void append(ObjectNode node) throws Exception {
        Files.writeString(Config.outputPath(), mapper.writeValueAsString(node) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static long bestEvaluation(SolveMetrics metrics, long fallback) {
        if (metrics instanceof AlnsRunMetrics alns) return alns.bestImprovements().isEmpty() ? 0L : alns.bestImprovements().getLast().evaluation();
        if (metrics instanceof AlnsChangeSwapVndHybridMetrics hybrid) {
            long offset = 0L;
            long best = 0L;
            RosterScore global = hybrid.stages().isEmpty() ? null : hybrid.stages().getFirst().inputScore();
            for (AlnsChangeSwapVndHybridMetrics.Stage stage : hybrid.stages()) {
                if (global != null && stage.outputScore().compareTo(global) > 0) {
                    best = offset + stageBestEvaluation(stage.stageMetrics(), stage.evaluationCount());
                    global = stage.outputScore();
                }
                offset += stage.evaluationCount();
            }
            return best;
        }
        return fallback;
    }

    private static long stageBestEvaluation(SolveMetrics metrics, long fallback) {
        if (metrics instanceof AlnsRunMetrics alns) {
            return alns.bestImprovements().isEmpty() ? fallback : alns.bestImprovements().getLast().evaluation();
        }
        if (metrics instanceof OrderedVndLocalSearchMetrics vnd) {
            return vnd.bestImprovements().isEmpty() ? fallback : vnd.bestImprovements().getLast().evaluation();
        }
        if (metrics instanceof FairnessRestrictedLocalSearchMetrics fairness) {
            return fairness.bestImprovements().isEmpty() ? fallback : fairness.bestImprovements().getLast().evaluation();
        }
        return fallback;
    }

    private static long elapsed(long started) { return Duration.ofNanos(System.nanoTime() - started).toMillis(); }

    private static ObjectNode metricsToJson(ObjectMapper mapper, SolveMetrics metrics) {
        ObjectNode node = mapper.createObjectNode();
        if (metrics == null) {
            node.put("engine_id", "NONE");
            return node;
        }
        node.put("engine_id", metrics.engineId());
        if (metrics instanceof AlnsRunMetrics alns) {
            node.put("evaluations", alns.searchEvaluations());
            node.put("accepted", alns.acceptedCandidates());
            node.put("rejected", alns.rejectedCandidates());
            node.put("rollback_attempts", alns.rollbackAttemptCount());
            node.put("score_mismatches", alns.scoreMismatchFailures());
            node.put("state_corruptions", alns.stateCorruptionFailures());
            node.put("final_best_events", alns.bestImprovements().size());
            ArrayNode destroy = node.putArray("destroy_operators");
            for (OperatorStatistics operator : alns.destroyOperators()) {
                ObjectNode item = destroy.addObject(); item.put("id", operator.operatorId()); item.put("selected", operator.selectionCount()); item.put("global_best", operator.globalBestCount()); item.put("final_best", !alns.bestImprovements().isEmpty() && alns.bestImprovements().getLast().destroyOperatorId().equals(operator.operatorId()));
            }
            ArrayNode repair = node.putArray("repair_operators");
            for (OperatorStatistics operator : alns.repairOperators()) {
                ObjectNode item = repair.addObject(); item.put("id", operator.operatorId()); item.put("selected", operator.selectionCount()); item.put("global_best", operator.globalBestCount()); item.put("final_best", !alns.bestImprovements().isEmpty() && alns.bestImprovements().getLast().repairOperatorId().equals(operator.operatorId()));
            }
        } else if (metrics instanceof OrderedVndLocalSearchMetrics vnd) {
            node.put("generated_reassign", vnd.generatedReassignCandidates()); node.put("generated_swap", vnd.generatedSwapCandidates());
            node.put("evaluated_reassign", vnd.evaluatedReassignCandidates()); node.put("evaluated_swap", vnd.evaluatedSwapCandidates());
            node.put("accepted_reassign", vnd.acceptedReassignCandidates()); node.put("accepted_swap", vnd.acceptedSwapCandidates());
            node.put("rejected", vnd.rejectedCandidates()); node.put("full_verifications", vnd.fullVerificationCount());
            node.put("score_mismatches", vnd.scoreMismatchFailures()); node.put("state_corruptions", vnd.stateCorruptionFailures());
            node.put("candidate_limit", vnd.candidateLimitPerNeighborhood()); node.put("final_best_events", vnd.bestImprovements().size());
        } else if (metrics instanceof FairnessRestrictedLocalSearchMetrics fairness) {
            node.put("evaluated", fairness.evaluatedCandidates()); node.put("accepted", fairness.acceptedCandidates()); node.put("rejected", fairness.rejectedCandidates());
            node.put("full_verifications", fairness.fullVerificationCount()); node.put("score_mismatches", fairness.scoreMismatchFailures());
            node.put("state_corruptions", fairness.stateCorruptionFailures()); node.put("selector_emitted", fairness.selectorMetrics().emittedCandidates()); node.put("final_best_events", fairness.bestImprovements().size());
        } else if (metrics instanceof AlnsChangeSwapVndHybridMetrics hybrid) {
            node.put("mode", hybrid.mode()); node.put("total_evaluations", hybrid.totalEvaluationCount());
            node.put("score_mismatches", hybrid.scoreMismatchFailures()); node.put("state_corruptions", hybrid.stateCorruptionFailures());
            ArrayNode stages = node.putArray("stages");
            for (AlnsChangeSwapVndHybridMetrics.Stage stage : hybrid.stages()) {
                ObjectNode item = stages.addObject(); item.put("id", stage.stageId()); item.put("seed", stage.derivedSeed());
                item.put("reserved_evaluations", stage.reservedEvaluationBudget()); item.put("available_evaluations", stage.availableEvaluationBudget()); item.put("used_evaluations", stage.evaluationCount()); item.put("unused_evaluations", stage.unusedEvaluationBudget()); item.put("reserved_wall_ms", stage.reservedWallMillis()); item.put("available_wall_ms", stage.availableWallMillis()); item.put("unused_wall_ms", stage.unusedWallMillis()); item.put("elapsed_ms", stage.elapsedMillis()); item.put("input_score", stage.inputScore().toString()); item.put("output_score", stage.outputScore().toString()); item.put("termination", stage.terminationReason().name()); item.set("metrics", metricsToJson(mapper, stage.stageMetrics()));
            }
        }
        return node;
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception failure) { throw new IllegalStateException("SHA-256 계산 실패", failure); }
    }

    private enum Candidate { ALNS_ONLY, ALNS_THEN_CHANGE_SWAP, ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS }
    private enum Profile { FIXED("fixed-evaluations"), WALL("wall-clock"); private final String id; Profile(String id) { this.id = id; } String id() { return id; } }

    private record Dataset(String name, PlanningRequest request, String hash) { }

    private record Pair(String dataset, long seed, int ordinal, boolean optaFirst, Run opta, Run candidate) {
        boolean comparable() { return opta.score() != null && candidate.score() != null; }
        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode node = mapper.createObjectNode(); node.put("record_type", "pair"); node.put("dataset", dataset); node.put("seed", seed);
            node.put("ordinal", ordinal); node.put("opta_first", optaFirst); node.put("candidate", candidate.engine()); node.put("comparable", comparable());
            if (comparable()) { node.put("outcome", candidate.score().compareTo(opta.score()) > 0 ? "WIN" : candidate.score().compareTo(opta.score()) < 0 ? "LOSS" : "TIE"); node.put("decisive_level", decisiveLevel(candidate.score(), opta.score())); }
            return node;
        }
    }

    private record Run(String engine, Candidate candidate, String dataset, String inputHash, long seed, RosterScore score,
            boolean feasible, long elapsedMillis, long evaluations, long bestEvaluation, long bestMillis,
            long rollbackAttempts, long mismatches, long corruptions, RosterScore initialScore,
            List<SolveMetrics> stageMetrics, SolveMetrics metrics, String error) {
        static Run opta(Dataset data, long seed, long elapsed, RosterScore score, long eval, long bestEval, long bestMillis) {
            return new Run("OPTAPLANNER", null, data.name(), data.hash(), seed, score, score.isFeasible(), elapsed, eval, bestEval, bestMillis, 0, 0, 0, null, List.of(), null, null);
        }
        static Run candidate(Candidate candidate, Dataset data, long seed, long elapsed, SolveResult<RosterSolution> result, long bestEval, long bestMillis, RosterScore initial) {
            SolveMetrics metrics = result.metrics(); List<SolveMetrics> stages = metrics instanceof AlnsChangeSwapVndHybridMetrics hybrid
                    ? hybrid.stages().stream().map(AlnsChangeSwapVndHybridMetrics.Stage::stageMetrics).toList() : List.of(metrics);
            long rollback = 0L, mismatch = 0L, corruption = 0L;
            if (metrics instanceof AlnsRunMetrics alns) { rollback = alns.rollbackAttemptCount(); mismatch = alns.scoreMismatchFailures(); corruption = alns.stateCorruptionFailures(); }
            if (metrics instanceof AlnsChangeSwapVndHybridMetrics hybrid) {
                mismatch = hybrid.scoreMismatchFailures(); corruption = hybrid.stateCorruptionFailures();
                rollback = stages.stream().mapToLong(Run::rollbackAttempts).sum();
            }
            return new Run(candidate.name(), candidate, data.name(), data.hash(), seed, result.score(), result.score().isFeasible(), elapsed,
                    result.evaluationCount(), bestEval, bestMillis, rollback, mismatch, corruption, initial, stages, metrics, null);
        }
        static Run failure(String engine, Dataset data, long seed, long elapsed, RuntimeException failure) {
            return new Run(engine, engine.equals("OPTAPLANNER") ? null : Candidate.valueOf(engine), data.name(), data.hash(), seed, null, false,
                    elapsed, 0, 0, 0, 0, 0, 0, null, List.of(), null, failure.getClass().getSimpleName());
        }
        boolean executionFailed() { return error != null; }
        private static long rollbackAttempts(SolveMetrics metrics) {
            if (metrics instanceof AlnsRunMetrics alns) return alns.rollbackAttemptCount();
            if (metrics instanceof OrderedVndLocalSearchMetrics vnd) return vnd.rollbackAttemptCount();
            if (metrics instanceof FairnessRestrictedLocalSearchMetrics fairness) return fairness.rollbackAttemptCount();
            return 0L;
        }
        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode node = mapper.createObjectNode(); node.put("record_type", "run"); node.put("engine", engine); if (candidate == null) node.putNull("candidate"); else node.put("candidate", candidate.name());
            node.put("dataset", dataset); node.put("input_sha256", inputHash); node.put("seed", seed); node.put("elapsed_ms", elapsedMillis); node.put("evaluation_count", evaluations); node.put("best_evaluation", bestEvaluation); node.put("best_ms", bestMillis);
            node.put("feasible", feasible); node.put("rollback_attempts", rollbackAttempts); node.put("score_mismatches", mismatches); node.put("state_corruptions", corruptions); if (score == null) node.putNull("score"); else { node.put("score", score.toString()); node.put("hard", score.hardScore()); node.set("soft", mapper.valueToTree(score.softScores())); }
            node.set("metrics", metricsToJson(mapper, metrics)); ArrayNode stageNodes = node.putArray("stage_metrics"); for (SolveMetrics stage : this.stageMetrics()) stageNodes.add(metricsToJson(mapper, stage)); if (error == null) node.putNull("error"); else node.put("error", error); return node;
        }
    }

    private record Config(List<String> datasets, List<Long> seeds, List<Candidate> candidates, Profile profile, long wallSeconds,
            long optaEvaluations, long pojoEvaluations, boolean warmup, String partition, Path output, Path summary, Path report) {
        static Config fromProperties() {
            List<String> datasets = csv("phase6.hybrid-vnd.datasets", DEFAULT_DATASETS); List<Long> seeds = csv("phase6.hybrid-vnd.seeds", List.of("901", "902")).stream().map(Long::parseLong).toList();
            List<Candidate> candidates = csv("phase6.hybrid-vnd.candidates", Arrays.stream(Candidate.values()).map(Enum::name).toList()).stream().map(value -> Candidate.valueOf(value.toUpperCase(Locale.ROOT))).toList();
            String value = System.getProperty("phase6.hybrid-vnd.profile", "wall").toLowerCase(Locale.ROOT); Profile profile = value.startsWith("fixed") ? Profile.FIXED : Profile.WALL;
            long wall = Long.getLong("phase6.hybrid-vnd.wall-seconds", 60L); long opta = Long.getLong("phase6.hybrid-vnd.opta-evaluations", 50_000L); long pojo = Long.getLong("phase6.hybrid-vnd.pojo-evaluations", 50_000L);
            boolean warmup = Boolean.parseBoolean(System.getProperty("phase6.hybrid-vnd.warmup", "true")); String partition = System.getProperty("phase6.hybrid-vnd.partition", "development"); Path output = Path.of(System.getProperty("phase6.hybrid-vnd.output", "target/benchmarks/hybrid-vnd/raw.jsonl"));
            return new Config(datasets, seeds, candidates, profile, wall, opta, pojo, warmup, partition, output, Path.of(System.getProperty("phase6.hybrid-vnd.summary", output.resolveSibling("summary.json").toString())), Path.of(System.getProperty("phase6.hybrid-vnd.report", output.resolveSibling("report.md").toString())));
        }
        static List<String> csv(String property, List<String> defaults) { return Arrays.stream(System.getProperty(property, String.join(",", defaults)).split(",")).map(String::strip).filter(value -> !value.isEmpty()).toList(); }
        static Path outputPath() { return Path.of(System.getProperty("phase6.hybrid-vnd.output", "target/benchmarks/hybrid-vnd/raw.jsonl")); }
        String fingerprint() { return sha256((datasets + "|" + seeds + "|" + candidates + "|" + profile + "|" + wallSeconds + "|" + optaEvaluations + "|" + pojoEvaluations).getBytes(StandardCharsets.UTF_8)); }
    }
}
