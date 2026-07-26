package org.acme.solver.lahc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.EmployeeScheduleBuilder;
import org.acme.model.EmployeeSchedule;
import org.acme.solver.adapter.PlanningProblemMapper;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.score.FullScoreCalculator;
import org.acme.test.JsonLoader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 이전 bounded witness seed가 작은 guided budget에서도 실제 발견되는지 고정합니다. */
@Tag("benchmark")
class FairnessHotspotGuidedProtectedReassignRegressionTest {

    @ParameterizedTest
    @ValueSource(longs = { 601L, 602L })
    void 이전_witness_seed는_256개_이내_guided_후보에서_발견된다(long seed) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("fairness.hotspot.regression.enabled"),
                "명시적 fairness hotspot regression에서만 긴 warm start를 재생성합니다.");
        PlanningRequest request = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().readValue(
                JsonLoader.loadAsString("/json/fairness.json"), PlanningRequest.class);
        EmployeeSchedule schedule = new EmployeeScheduleBuilder().build(request);
        PlanningProblem problem = new PlanningProblemMapper().toPlanningProblem(schedule);
        SolveResult<RosterSolution> incumbentResult = new OptaStyleLocalSearchEngine().solve(problem,
                SolveOptions.builder().maxEvaluations(100_000L).randomSeed(seed).build(), ignored -> { });
        RosterSolution incumbent = incumbentResult.bestSolution();

        SolveResult<RosterSolution> result = FairnessRestrictedLocalSearchEngine.hotspotGuidedProtectedReassign().solve(
                problem, SolveOptions.builder().warmStart(incumbent).maxEvaluations(256L).randomSeed(seed).build(),
                ignored -> { });
        FairnessRestrictedLocalSearchMetrics metrics = (FairnessRestrictedLocalSearchMetrics) result.metrics();

        assertEquals(0, result.score().hardScore());
        assertEquals(incumbent.score().softScore(0), result.score().softScore(0));
        assertEquals(incumbent.score().softScore(1), result.score().softScore(1));
        assertTrue(result.score().softScore(2) > incumbent.score().softScore(2), result::toString);
        assertTrue(metrics.selectorMetrics().emittedCandidates() <= 256L, metrics::toString);
        assertEquals(0L, metrics.scoreMismatchFailures());
        assertEquals(0L, metrics.stateCorruptionFailures());
        assertEquals(result.score(), new FullScoreCalculator().calculateScore(problem, result.bestSolution()));
    }
}
