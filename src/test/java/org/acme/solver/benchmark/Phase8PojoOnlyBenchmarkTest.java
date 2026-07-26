package org.acme.solver.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.EmployeeScheduleBuilder;
import org.acme.solver.adapter.PlanningProblemMapper;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.lahc.AlnsChangeSwapVndHybridMetrics;
import org.acme.solver.lahc.AlnsChangeSwapVndHybridSolverEngine;
import org.acme.solver.score.FullScoreCalculator;
import org.acme.test.JsonLoader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Tag("benchmark")
class Phase8PojoOnlyBenchmarkTest {

    private static final List<String> DATASETS =
            List.of("fairness.json", "preceptor.json", "request.json", "sample.json");
    private static final String CANDIDATE = "ALNS_THEN_ORDERED_VND_PRECEPTOR_PREFIX_REASSIGN";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final FullScoreCalculator full = new FullScoreCalculator();

    @Test
    void 네_운영입력의_POJO_ONLY_고정평가_benchmark를_기록한다() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("phase8.benchmark.enabled"),
                "Phase 8 benchmark 명시 실행에서만 artifact를 생성합니다.");
        Path outputDir = Path.of(requiredProperty("phase8.benchmark.output-dir")).toAbsolutePath();
        long evaluations = Long.getLong("phase8.benchmark.evaluations", 5_000L);
        long seed = Long.getLong("phase8.benchmark.seed", 1_701L);
        Files.createDirectories(outputDir);

        List<ObjectNode> runs = new ArrayList<>();
        for (String dataset : DATASETS) {
            runs.add(run(dataset, seed, evaluations));
        }

        writeRaw(outputDir, seed, evaluations, runs);
        writeSummary(outputDir, seed, evaluations, runs);
        writeReport(outputDir, seed, evaluations, runs);
    }

    private ObjectNode run(String dataset, long seed, long evaluations) throws Exception {
        String json = JsonLoader.loadAsString("/json/" + dataset);
        PlanningRequest request = objectMapper.readValue(json, PlanningRequest.class);
        PlanningProblem problem = new PlanningProblemMapper().toPlanningProblem(
                new EmployeeScheduleBuilder().build(request));
        AlnsChangeSwapVndHybridSolverEngine engine = new AlnsChangeSwapVndHybridSolverEngine(
                AlnsChangeSwapVndHybridSolverEngine.Mode
                        .ALNS_THEN_ORDERED_VND_WITH_PRECEPTOR_PREFIX_REASSIGN);
        SolveResult<RosterSolution> result = engine.solve(
                problem,
                SolveOptions.builder().maxEvaluations(evaluations).randomSeed(seed).build(),
                ignored -> { });

        assertNotNull(result.bestSolution(), dataset);
        assertEquals(0, result.score().hardScore(), dataset + ": " + result.score());
        assertEquals(full.calculateScore(problem, result.bestSolution()), result.score(), dataset);
        AlnsChangeSwapVndHybridMetrics metrics =
                (AlnsChangeSwapVndHybridMetrics) result.metrics();
        assertEquals(0L, metrics.scoreMismatchFailures(), dataset);
        assertEquals(0L, metrics.stateCorruptionFailures(), dataset);

        ObjectNode run = objectMapper.createObjectNode();
        run.put("record_type", "run");
        run.put("engine", "POJO_ONLY");
        run.put("candidate", CANDIDATE);
        run.put("dataset", dataset);
        run.put("input_sha256", sha256(json));
        run.put("seed", seed);
        run.put("evaluation_budget", evaluations);
        run.put("evaluation_count", result.evaluationCount());
        run.put("elapsed_ms", result.elapsedMillis());
        run.put("termination_reason", result.terminationReason().name());
        run.put("complete", isComplete(result.bestSolution()));
        run.put("full_score_verified", true);
        run.put("hard", result.score().hardScore());
        ArrayNode soft = run.putArray("soft");
        result.score().softScores().forEach(soft::add);
        run.put("score", result.score().toString());
        run.put("score_mismatches", metrics.scoreMismatchFailures());
        run.put("state_corruptions", metrics.stateCorruptionFailures());
        run.put("rollback_failures", metrics.rollbackFailureCount());
        return run;
    }

    private void writeRaw(Path outputDir, long seed, long evaluations, List<ObjectNode> runs) throws Exception {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("record_type", "metadata");
        metadata.put("schema_version", 1);
        metadata.put("captured_at", Instant.now().toString());
        metadata.put("profile", "fixed-evaluations");
        metadata.put("engine", "POJO_ONLY");
        metadata.put("candidate", CANDIDATE);
        metadata.put("seed", seed);
        metadata.put("evaluation_budget", evaluations);
        metadata.put("lexicographic_order", "hard > soft[0] > soft[1] > soft[2] > soft[3]");
        StringBuilder raw = new StringBuilder(objectMapper.writeValueAsString(metadata)).append('\n');
        for (ObjectNode run : runs) {
            raw.append(objectMapper.writeValueAsString(run)).append('\n');
        }
        Files.writeString(outputDir.resolve("raw.jsonl"), raw, StandardCharsets.UTF_8);
    }

    private void writeSummary(Path outputDir, long seed, long evaluations, List<ObjectNode> runs)
            throws Exception {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("schema_version", 1);
        summary.put("verdict", "PASS");
        summary.put("engine", "POJO_ONLY");
        summary.put("candidate", CANDIDATE);
        summary.put("seed", seed);
        summary.put("evaluation_budget", evaluations);
        summary.put("datasets", runs.size());
        summary.put("feasible", runs.stream().filter(run -> run.path("hard").asInt() == 0).count());
        summary.put("score_mismatches", runs.stream().mapToLong(run -> run.path("score_mismatches").asLong()).sum());
        summary.put("state_corruptions", runs.stream().mapToLong(run -> run.path("state_corruptions").asLong()).sum());
        summary.put("rollback_failures", runs.stream().mapToLong(run -> run.path("rollback_failures").asLong()).sum());
        summary.set("runs", objectMapper.valueToTree(runs));
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(outputDir.resolve("summary.json").toFile(), summary);
    }

    private void writeReport(Path outputDir, long seed, long evaluations, List<ObjectNode> runs)
            throws Exception {
        StringBuilder report = new StringBuilder()
                .append("# Phase 8 POJO_ONLY 운영 입력 benchmark\n\n")
                .append("- 판정: **PASS**\n")
                .append("- profile: `fixed-evaluations`\n")
                .append("- candidate: `").append(CANDIDATE).append("`\n")
                .append("- seed: `").append(seed).append("`\n")
                .append("- evaluation budget: `").append(evaluations).append("`\n")
                .append("- 비교: `hard > soft[0] > soft[1] > soft[2] > soft[3]`\n\n")
                .append("| dataset | score | elapsed ms | evaluations | full verified | integrity failures |\n")
                .append("|---|---|---:|---:|---:|---:|\n");
        for (ObjectNode run : runs) {
            report.append("| ").append(run.path("dataset").asText())
                    .append(" | `").append(run.path("score").asText()).append("`")
                    .append(" | ").append(run.path("elapsed_ms").asLong())
                    .append(" | ").append(run.path("evaluation_count").asLong())
                    .append(" | ").append(run.path("full_score_verified").asBoolean())
                    .append(" | ").append(run.path("score_mismatches").asLong()
                            + run.path("state_corruptions").asLong()
                            + run.path("rollback_failures").asLong())
                    .append(" |\n");
        }
        Files.writeString(outputDir.resolve("report.md"), report, StandardCharsets.UTF_8);
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " system property가 필요합니다.");
        }
        return value;
    }

    private static boolean isComplete(RosterSolution solution) {
        for (int employeeIndex : solution.employeeIndexByShift()) {
            if (employeeIndex < 0) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
