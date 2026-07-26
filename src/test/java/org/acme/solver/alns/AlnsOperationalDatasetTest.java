package org.acme.solver.alns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.EmployeeScheduleBuilder;
import org.acme.solver.adapter.PlanningProblemMapper;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.score.FullScoreCalculator;
import org.acme.test.JsonLoader;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.ObjectMapper;

class AlnsOperationalDatasetTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    @ParameterizedTest
    @MethodSource("datasetsAndSeeds")
    void 운영입력과_seed별로_verified_feasible_best와_operator_metric을_반환한다(
            String dataset, long seed) throws Exception {
        PlanningRequest request = OBJECT_MAPPER.readValue(
                JsonLoader.loadAsString("/json/" + dataset), PlanningRequest.class);
        PlanningProblem problem = new PlanningProblemMapper().toPlanningProblem(
                new EmployeeScheduleBuilder().build(request));
        AlnsSolverEngine engine = new AlnsSolverEngine();
        engine.calibrationAttempts = 8;
        engine.segmentLength = 10;

        SolveResult<RosterSolution> result = engine.solve(
                problem,
                SolveOptions.builder().maxEvaluations(30L).randomSeed(seed).build(),
                ignored -> {
                });

        assertNotNull(result.bestSolution(), dataset + "/" + seed + ": " + result);
        assertEquals(0, result.score().hardScore(), dataset + "/" + seed);
        assertEquals(
                new FullScoreCalculator().calculateScore(problem, result.bestSolution()),
                result.score(),
                dataset + "/" + seed);
        AlnsRunMetrics metrics = assertInstanceOf(AlnsRunMetrics.class, result.metrics());
        assertEquals(3, metrics.destroyOperators().size());
        assertEquals(3, metrics.repairOperators().size());
        assertTrue(metrics.destroyOperators().stream()
                .allMatch(stat -> Double.isFinite(stat.weight()) && stat.weight() > 0.0d));
        assertTrue(metrics.repairOperators().stream()
                .allMatch(stat -> Double.isFinite(stat.weight()) && stat.weight() > 0.0d));
        if (seed == 42L || seed == 20260716L) {
            System.out.printf(
                    "PHASE6_SA dataset=%s seed=%d hardScale=%.3f softScales=%s "
                            + "estimatedP0=%s observedP0=%.3f destroy=%s repair=%s best=%d%n",
                    dataset,
                    seed,
                    metrics.calibration().hardScale(),
                    java.util.Arrays.toString(metrics.calibration().softScales()),
                    java.util.Arrays.toString(metrics.calibration().estimatedInitialAcceptanceRates()),
                    metrics.observedInitialWorseningAcceptanceRate(),
                    metrics.destroyOperators(),
                    metrics.repairOperators(),
                    metrics.bestImprovements().size());
        }
    }

    private static Stream<Arguments> datasetsAndSeeds() {
        return Stream.of("fairness.json", "preceptor.json", "request.json", "sample.json")
                .flatMap(dataset -> Stream.of(
                        0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 42L, 20260716L)
                        .map(seed -> Arguments.of(Named.of(dataset, dataset), seed)));
    }
}
