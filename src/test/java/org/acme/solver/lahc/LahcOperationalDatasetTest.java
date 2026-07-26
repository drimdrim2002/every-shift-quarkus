package org.acme.solver.lahc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicReference;

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

class LahcOperationalDatasetTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final long EVALUATION_BUDGET = 10_000L;

    @ParameterizedTest
    @MethodSource("datasets")
    void 운영_입력은_hard_0이고_고정_평가_프로필이_재현된다(String dataset) throws Exception {
        PlanningRequest request = OBJECT_MAPPER.readValue(
                JsonLoader.loadAsString("/json/" + dataset), PlanningRequest.class);
        PlanningProblem problem = new PlanningProblemMapper().toPlanningProblem(
                new EmployeeScheduleBuilder().build(request));
        SolveOptions options = SolveOptions.builder()
                .maxEvaluations(EVALUATION_BUDGET)
                .randomSeed(42L)
                .build();

        AtomicReference<RosterSolution> initialSnapshot = new AtomicReference<>();
        SolveResult<RosterSolution> first = new LahcSolverEngine().solve(
                problem, options, solution -> initialSnapshot.compareAndSet(null, solution));
        SolveResult<RosterSolution> second = new LahcSolverEngine().solve(problem, options, solution -> {
        });

        if (first.score().hardScore() < 0) {
            var hardBreakdown = new FullScoreCalculator()
                    .calculateWithBreakdown(problem, first.bestSolution())
                    .scoreByConstraintId()
                    .entrySet()
                    .stream()
                    .filter(entry -> entry.getValue().hardScore() < 0)
                    .toList();
            System.out.printf("PHASE5_LAHC_HARD_BREAKDOWN dataset=%s constraints=%s%n",
                    dataset, hardBreakdown);
        }

        assertNotNull(first.bestSolution());
        assertEquals(0, first.score().hardScore(), () -> dataset + ": " + first.score());
        assertArrayEquals(first.bestSolution().employeeIndexByShift(), second.bestSolution().employeeIndexByShift());
        assertEquals(first.score(), second.score());
        assertEquals(first.evaluationCount(), second.evaluationCount());
        System.out.printf(
                "PHASE5_LAHC dataset=%s initialScore=%s score=%s feasible=%s elapsedMs=%d evaluations=%d reason=%s%n",
                dataset,
                initialSnapshot.get().score(),
                first.score(),
                first.score().isFeasible(),
                first.elapsedMillis(),
                first.evaluationCount(),
                first.terminationReason());
    }

    private static Stream<Arguments> datasets() {
        return Stream.of("fairness.json", "preceptor.json", "request.json", "sample.json")
                .map(dataset -> Arguments.of(Named.of(dataset, dataset)));
    }
}
