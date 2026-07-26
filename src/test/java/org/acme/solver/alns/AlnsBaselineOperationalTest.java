package org.acme.solver.alns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.EmployeeScheduleBuilder;
import org.acme.solver.adapter.PlanningProblemMapper;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.lahc.LahcSolverEngine;
import org.acme.solver.move.SearchState;
import org.acme.solver.move.SolutionFingerprint;
import org.acme.solver.score.FullScoreCalculator;
import org.acme.solver.score.IncrementalScoreCalculator;
import org.acme.test.JsonLoader;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.ObjectMapper;

class AlnsBaselineOperationalTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    @ParameterizedTest
    @MethodSource("datasets")
    void Phase5_feasible_current에서_baseline_operator는_complete_candidate를_생성한다(
            String dataset) throws Exception {
        PlanningRequest request = OBJECT_MAPPER.readValue(
                JsonLoader.loadAsString("/json/" + dataset), PlanningRequest.class);
        PlanningProblem problem = new PlanningProblemMapper().toPlanningProblem(
                new EmployeeScheduleBuilder().build(request));
        RosterSolution phase5 = new LahcSolverEngine().solve(
                problem,
                SolveOptions.builder().maxEvaluations(10_000L).randomSeed(42L).build(),
                ignored -> {
                }).bestSolution();
        assertNotNull(phase5);
        assertEquals(0, phase5.score().hardScore(), dataset);

        FullScoreCalculator full = new FullScoreCalculator();
        SearchState state = new SearchState(problem, phase5);
        IncrementalScoreCalculator incremental = new IncrementalScoreCalculator(problem, phase5, full);
        AlnsIteration iteration = new AlnsIteration(
                problem, state, incremental, OperatorCompatibilityMatrix.baseline());
        AlnsIterationConfig config = new AlnsIterationConfig(0.05d, 1, 8, 12, 2);
        List<OperatorPair> pairs = List.of(
                new OperatorPair(new RandomRemoval(), new GreedyRepair()),
                new OperatorPair(new RandomRemoval(), new Regret2Repair()),
                new OperatorPair(new RelatedShiftRemoval(), new RelationAwareRepair()),
                new OperatorPair(new PreceptorRelationGroupRemoval(), new RelationAwareRepair()));
        int repairFailures = 0;

        for (int index = 0; index < pairs.size(); index++) {
            OperatorPair pair = pairs.get(index);
            AlnsIterationResult result = iteration.execute(
                    pair.destroy(), pair.repair(), new ImprovementOnlyAcceptance(),
                    config, new Random(20260715L + index), () -> false);
            if (result.status() == AlnsIterationStatus.REPAIR_FAILED
                    || result.status() == AlnsIterationStatus.OPERATOR_EXCEPTION) {
                repairFailures++;
            }
            assertNotNull(result.candidateScore(), () -> dataset + ": " + result);
            assertEquals(0, state.score().hardScore(), dataset);
            assertEquals(full.calculateScore(problem, state.snapshot()), state.score(), dataset);
            assertEquals(SolutionFingerprint.from(state.snapshot()), state.fingerprint(), dataset);
            assertEquals(state.score(), incremental.currentScore(), dataset);
        }

        System.out.printf(
                "PHASE6A_OPERATIONAL dataset=%s iterations=%d repairFailures=%d rollbackMismatch=0 score=%s%n",
                dataset, pairs.size(), repairFailures, state.score());
        assertEquals(0, repairFailures, dataset);
    }

    private static Stream<Arguments> datasets() {
        return Stream.of("fairness.json", "preceptor.json", "request.json", "sample.json")
                .map(dataset -> Arguments.of(Named.of(dataset, dataset)));
    }

    private record OperatorPair(DestroyOperator destroy, RepairOperator repair) {
    }
}
