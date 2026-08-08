package org.acme.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.EmployeeScheduleBuilder;
import org.acme.solver.adapter.PlanningProblemMapper;
import org.acme.solver.alns.AlnsSolverEngine;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.core.SolverEngine;
import org.acme.solver.core.TerminationReason;
import org.acme.solver.initial.InitialSolutionBuilder;
import org.acme.solver.initial.InitialSolutionResult;
import org.acme.solver.lahc.AlnsChangeSwapVndHybridSolverEngine;
import org.acme.solver.score.ConstraintContribution;
import org.acme.solver.score.ConstraintIds;
import org.acme.solver.score.FullScoreCalculator;
import org.acme.solver.score.ScoreCalculationResult;
import org.acme.solver.score.ScoreLevel;
import org.acme.test.JsonLoader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 엔진 성능(시간/평가 예산) 부족이 hard 위반으로 이어지는지 진단한다.
 *
 * <p>관점:
 * <ul>
 *   <li>초기해가 이미 infeasible인가? (구조/구축 실패 vs 탐색 실패)</li>
 *   <li>짧은 wall-clock / 낮은 evaluation budget 에서 hard&lt;0 인가?</li>
 *   <li>예산을 늘리면 hard=0 으로 회복되는가? (성능/탐색 부족 가설)</li>
 *   <li>ALNS-only vs Hybrid(ALNS→VND→prefix) 가 hard 회복 패턴이 다른가?</li>
 * </ul>
 *
 * <p>실행: {@code ./mvnw -Dtest=HardFeasibilityBudgetDiagnosticTest test}
 */
@Tag("hard-feasibility-diagnostic")
class HardFeasibilityBudgetDiagnosticTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final FullScoreCalculator FULL = new FullScoreCalculator();
    private static final long SEED = 42L;

    /** 운영 재현 + hard 취약 데이터셋(문서상 짧은 예산 infeasible 이력). */
    static Stream<Arguments> datasets() {
        return Stream.of(
                Arguments.of("remote",
                        "/json/remote/3e56517c-2682-4ee2-a89f-310a3813b983.json"),
                Arguments.of("preceptor", "/json/preceptor.json"),
                Arguments.of("request", "/json/request.json"),
                Arguments.of("fairness", "/json/fairness.json"));
    }

    @ParameterizedTest(name = "initial_hard[{0}]")
    @MethodSource("datasets")
    void 초기해_hard_및_constraint_breakdown을_기록한다(String name, String resource) throws Exception {
        PlanningProblem problem = loadProblem(resource);
        InitialSolutionResult initial = new InitialSolutionBuilder(FULL).build(problem);
        assertTrue(initial.succeeded(), name + ": initial solution must succeed");

        RosterSolution solution = initial.solution();
        ScoreCalculationResult breakdown = FULL.calculateWithBreakdown(problem, solution);
        assertEquals(solution.score(), breakdown.score(), name + ": stored vs full score");

        System.out.printf("INITIAL dataset=%s score=%s feasible=%s hardBreakdown=%s%n",
                name, solution.score(), solution.score().isFeasible(),
                hardBreakdown(breakdown));

        // 초기 구축이 항상 hard=0 을 보장하지는 않음. 다만 full score 일치만 강제.
        assertEquals(breakdown.score().hardScore(), solution.score().hardScore());
    }

    @ParameterizedTest(name = "budget_curve[{0}]")
    @MethodSource("datasets")
    void wall_clock_및_fixed_eval_budget별_hard_회복_곡선을_측정한다(String name, String resource)
            throws Exception {
        PlanningProblem problem = loadProblem(resource);
        InitialSolutionResult initialResult = new InitialSolutionBuilder(FULL).build(problem);
        assertTrue(initialResult.succeeded(), name);
        RosterSolution initial = initialResult.solution();
        int initialHard = initial.score().hardScore();

        SolverEngine hybrid = new AlnsChangeSwapVndHybridSolverEngine(
                AlnsChangeSwapVndHybridSolverEngine.Mode
                        .ALNS_THEN_ORDERED_VND_WITH_PRECEPTOR_PREFIX_REASSIGN);
        SolverEngine alns = new AlnsSolverEngine();

        List<String> rows = new ArrayList<>();
        rows.add(String.format("dataset=%s shifts=%d employees=%d initialHard=%d initialScore=%s",
                name, problem.shiftCount(), problem.employeeCount(),
                initialHard, initial.score()));

        // Wall-clock: 짧은 → 중간. hard 취약 데이터셋(preceptor/remote)만 full curve.
        List<Long> wallSeconds = ("preceptor".equals(name) || "remote".equals(name))
                ? List.of(1L, 2L, 5L, 15L)
                : List.of(2L, 15L);
        for (long seconds : wallSeconds) {
            rows.add(runWall(name, "HYBRID", hybrid, problem, initial, seconds, FULL));
            rows.add(runWall(name, "ALNS", alns, problem, initial, seconds, FULL));
        }

        // Fixed evaluations: 알고리즘 품질 축 (wall-clock 비결정 제거)
        List<Long> evalBudgets = ("preceptor".equals(name) || "remote".equals(name))
                ? List.of(50L, 200L, 1000L, 5000L)
                : List.of(200L, 2000L);
        for (long evals : evalBudgets) {
            rows.add(runEvals(name, "HYBRID", hybrid, problem, initial, evals, FULL));
            rows.add(runEvals(name, "ALNS", alns, problem, initial, evals, FULL));
        }

        System.out.println("=== HARD FEASIBILITY CURVE " + name + " ===");
        rows.forEach(System.out::println);

        // 가설 판정 보조: 15s hybrid 는 운영 데이터셋에서 hard=0 이어야 한다.
        // (원격 스냅샷 hard=0, Phase6 final 8/8 feasible 과 정합)
        SolveResult<RosterSolution> hybrid15 = hybrid.solve(problem,
                SolveOptions.builder()
                        .warmStart(initial)
                        .spentLimit(Duration.ofSeconds(15))
                        .randomSeed(SEED)
                        .build(),
                ignored -> {
                });
        assertNotNull(hybrid15.bestSolution(), name + " hybrid@15s null best: " + hybrid15);
        assertEquals(0, hybrid15.score().hardScore(),
                name + " hybrid@15s must be hard-feasible, got " + hybrid15.score()
                        + " reason=" + hybrid15.terminationReason());
        assertTrue(hybrid15.score().isFeasible());
    }

    /**
     * hard 악화가 SA로 수락될 수 있는 구간(feasible lock 전)과,
     * 한 번 feasible을 본 뒤 hard 악화가 차단되는지 단위 검증.
     */
    @Test
    void SA_feasible_region_lock은_최초_feasible_이후_hard_악화를_거부한다() {
        org.acme.solver.alns.SaAcceptanceConfig config =
                new org.acme.solver.alns.SaAcceptanceConfig(0.2d, 0.01d, 10_000L);
        org.acme.solver.alns.SaCalibrationResult calibration =
                org.acme.solver.alns.SaCalibrationResult.fallback(1L, 0.2d);
        // RNG=0 → exp(-E/T) 수락 구간의 하한 통과 (확률 수락 시도 시 항상 accept)
        org.acme.solver.alns.LexicographicSaAcceptance unlocked =
                new org.acme.solver.alns.LexicographicSaAcceptance(
                        config, calibration, 100L, () -> 0.0d, false);
        org.acme.solver.alns.LexicographicSaAcceptance locked =
                new org.acme.solver.alns.LexicographicSaAcceptance(
                        config, calibration, 100L, () -> 0.0d, true);

        RosterScore infeasible = RosterScore.of(-10, 0, 0, 0, 0);
        RosterScore worseHard = RosterScore.of(-20, 0, 0, 0, 0);
        RosterScore betterHard = RosterScore.of(-5, 0, 0, 0, 0);
        RosterScore feasible = RosterScore.of(0, -100, 0, 0, 0);
        RosterScore hardWorsenFromFeasible = RosterScore.of(-1, 0, 0, 0, 0);

        // lock 전: hard 개선 수락, hard 악화도 SA로 수락 가능(RNG=0)
        assertTrue(unlocked.accept(infeasible, betterHard));
        assertTrue(unlocked.accept(betterHard, worseHard),
                "hard 악화는 feasible lock 전 SA 확률 수락 가능");

        // lock 후: infeasible 후보 전부 거부
        assertTrue(locked.feasibleRegionLocked());
        assertTrue(!locked.accept(feasible, hardWorsenFromFeasible),
                "feasible region lock must reject hard-worsening candidates");
        assertTrue(!locked.accept(feasible, infeasible));
    }

    /**
     * Hybrid는 infeasible globalBest 를 반환할 수 있고,
     * pure ALNS는 verified feasible best 만 반환(없으면 null + NO_FEASIBLE)한다.
     * 계약 차이를 고정한다.
     */
    @Test
    void ALNS는_feasible만_반환하고_Hybrid는_infeasible_best를_반환할_수_있다() throws Exception {
        // skill 없는 직원만 있는 tiny 문제 → 초기·최종 hard < 0 가능
        PlanningProblem.ScheduleWindow window = new PlanningProblem.ScheduleWindow(
                "t", "hard-only", 0, 2,
                java.time.LocalDate.of(2026, 1, 1),
                java.time.LocalDate.of(2025, 12, 31));
        PlanningProblem problem = new PlanningProblem(
                window,
                List.of(new PlanningProblem.EmployeeData(
                        "e0", "e0", java.util.Set.of("ASSISTANT"), java.util.Set.of("D"),
                        0, 0, 0, 0, null)),
                List.of(new PlanningProblem.ShiftData(
                        1L, "s0", "D",
                        java.time.LocalDateTime.of(2026, 1, 1, 8, 0),
                        java.time.LocalDateTime.of(2026, 1, 1, 16, 0),
                        java.time.LocalDate.of(2026, 1, 1),
                        "loc", "RN", false, 0, 0, 0)),
                List.of());

        InitialSolutionResult built = new InitialSolutionBuilder(FULL).build(problem);
        // skill 불일치로 hard < 0 이거나 구축 실패
        if (!built.succeeded()) {
            System.out.println("TINY skill-mismatch: initial build failed (acceptable)");
            return;
        }
        assertTrue(built.solution().score().hardScore() < 0,
                "expected infeasible initial for skill mismatch");

        AlnsSolverEngine alns = new AlnsSolverEngine();
        SolveResult<RosterSolution> alnsResult = alns.solve(problem,
                SolveOptions.builder()
                        .warmStart(built.solution())
                        .maxEvaluations(20L)
                        .randomSeed(1L)
                        .build(),
                ignored -> {
                });
        // feasible 해가 구조적으로 없으면 null + NO_FEASIBLE
        if (alnsResult.bestSolution() == null) {
            assertEquals(TerminationReason.NO_FEASIBLE_SOLUTION, alnsResult.terminationReason());
        } else {
            assertTrue(alnsResult.score().isFeasible(),
                    "ALNS must not return infeasible best: " + alnsResult.score());
        }

        SolverEngine hybrid = new AlnsChangeSwapVndHybridSolverEngine(
                AlnsChangeSwapVndHybridSolverEngine.Mode.ALNS_THEN_CHANGE_SWAP);
        SolveResult<RosterSolution> hybridResult = hybrid.solve(problem,
                SolveOptions.builder()
                        .warmStart(built.solution())
                        .maxEvaluations(20L)
                        .randomSeed(1L)
                        .build(),
                ignored -> {
                });
        assertNotNull(hybridResult.bestSolution(), "hybrid returns globalBest even if infeasible");
        if (!hybridResult.score().isFeasible()) {
            assertEquals(TerminationReason.NO_FEASIBLE_SOLUTION, hybridResult.terminationReason());
        }
        System.out.printf("CONTRACT alns=%s hybrid=%s/%s%n",
                alnsResult.terminationReason(),
                hybridResult.score(), hybridResult.terminationReason());
    }

    private static String runWall(
            String dataset, String engineName, SolverEngine engine,
            PlanningProblem problem, RosterSolution warm, long seconds,
            FullScoreCalculator full) {
        long t0 = System.nanoTime();
        List<RosterScore> bestTrail = new ArrayList<>();
        SolveResult<RosterSolution> result = engine.solve(problem,
                SolveOptions.builder()
                        .warmStart(warm)
                        .spentLimit(Duration.ofSeconds(seconds))
                        .randomSeed(SEED)
                        .build(),
                sol -> bestTrail.add(sol.score()));
        long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        return formatRow(dataset, engineName, "wall=" + seconds + "s", problem, result, ms,
                bestTrail, full);
    }

    private static String runEvals(
            String dataset, String engineName, SolverEngine engine,
            PlanningProblem problem, RosterSolution warm, long evals,
            FullScoreCalculator full) {
        long t0 = System.nanoTime();
        List<RosterScore> bestTrail = new ArrayList<>();
        SolveResult<RosterSolution> result = engine.solve(problem,
                SolveOptions.builder()
                        .warmStart(warm)
                        .maxEvaluations(evals)
                        .randomSeed(SEED)
                        .build(),
                sol -> bestTrail.add(sol.score()));
        long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        return formatRow(dataset, engineName, "evals=" + evals, problem, result, ms,
                bestTrail, full);
    }

    private static String formatRow(
            String dataset, String engineName, String budget, PlanningProblem problem,
            SolveResult<RosterSolution> result, long ms, List<RosterScore> trail,
            FullScoreCalculator full) {
        RosterSolution best = result.bestSolution();
        String score = best == null ? "null" : best.score().toString();
        int hard = best == null ? Integer.MIN_VALUE : best.score().hardScore();
        String trailHard = trail.stream()
                .map(s -> Integer.toString(s.hardScore()))
                .reduce((a, b) -> a + "→" + b)
                .orElse("-");
        String hardDetail = " trailHard=[" + trailHard + "]";
        if (best != null && hard < 0) {
            ScoreCalculationResult br = full.calculateWithBreakdown(problem, best);
            hardDetail += " hardConstraints=" + hardBreakdown(br);
        }
        return String.format(
                "ROW dataset=%s engine=%s budget=%s hard=%s score=%s reason=%s evals=%d elapsedMs=%d%s",
                dataset, engineName, budget, hard == Integer.MIN_VALUE ? "NA" : hard,
                score, result.terminationReason(), result.evaluationCount(), ms, hardDetail);
    }

    private static Map<String, Integer> hardBreakdown(ScoreCalculationResult result) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (ConstraintContribution c : result.contributions()) {
            if (c.level() == ScoreLevel.HARD) {
                map.merge(c.constraintId(), c.contribution(), Integer::sum);
            }
        }
        // 안정 순서로 주요 hard id 표시
        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (String id : List.of(
                ConstraintIds.REQUIRED_SKILL,
                ConstraintIds.OVERLAP,
                ConstraintIds.MINIMUM_REST,
                ConstraintIds.CONSECUTIVE_NIGHT,
                ConstraintIds.MONTHLY_NIGHT_LIMIT,
                ConstraintIds.ONE_SHIFT_PER_DAY,
                ConstraintIds.PRECEPTEE_PAIR,
                ConstraintIds.PRECEPTOR_PAIR,
                ConstraintIds.POST_NIGHT_RECOVERY)) {
            if (map.containsKey(id)) {
                ordered.put(shortId(id), map.get(id));
            }
        }
        map.forEach((k, v) -> ordered.putIfAbsent(shortId(k), v));
        return ordered;
    }

    private static String shortId(String id) {
        if (id.length() <= 28) {
            return id;
        }
        return id.substring(0, 28) + "…";
    }

    private static PlanningProblem loadProblem(String resource) throws Exception {
        PlanningRequest request = MAPPER.readValue(JsonLoader.loadAsString(resource), PlanningRequest.class);
        return new PlanningProblemMapper().toPlanningProblem(
                new EmployeeScheduleBuilder().build(request));
    }
}
