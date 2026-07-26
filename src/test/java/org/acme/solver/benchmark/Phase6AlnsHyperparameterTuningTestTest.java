package org.acme.solver.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.acme.solver.benchmark.Phase6AlnsHyperparameterTuningTest.AlnsObservation;
import org.acme.solver.benchmark.Phase6AlnsHyperparameterTuningTest.Ranking;
import org.acme.solver.benchmark.Phase6AlnsHyperparameterTuningTest.SearchConfig;
import org.acme.solver.benchmark.Phase6AlnsHyperparameterTuningTest.TuningCase;
import org.acme.solver.benchmark.Phase6AlnsHyperparameterTuningTest.TuningConfig;
import org.acme.solver.core.RosterScore;
import org.junit.jupiter.api.Test;

class Phase6AlnsHyperparameterTuningTestTest {

    @Test
    void 사전식_우위가_실행시간보다_먼저다() {
        SearchConfig baseline = SearchConfig.baseline();
        SearchConfig slowerButBetter = baseline.withSa(0.35d, 0.005d);
        TuningCase tuningCase = testCase();

        List<Ranking> rankings = Phase6AlnsHyperparameterTuningTest.rank(
                List.of(baseline, slowerButBetter),
                List.of(tuningCase),
                List.of(
                        observation(baseline, tuningCase, -10, 1L),
                        observation(slowerButBetter, tuningCase, -9, 999L)));

        assertEquals(slowerButBetter.id(), rankings.getFirst().configId());
    }

    @Test
    void 품질이_동률이면_실행시간_노이즈가_아니라_기본값을_우선한다() {
        SearchConfig baseline = SearchConfig.baseline();
        SearchConfig fasterChanged = baseline.withSa(0.35d, 0.005d);
        TuningCase tuningCase = testCase();

        List<Ranking> rankings = Phase6AlnsHyperparameterTuningTest.rank(
                List.of(fasterChanged, baseline),
                List.of(tuningCase),
                List.of(
                        observation(fasterChanged, tuningCase, -10, 1L),
                        observation(baseline, tuningCase, -10, 999L)));

        assertEquals(baseline.id(), rankings.getFirst().configId());
        assertEquals(0, rankings.getFirst().changedParameterCount());
    }

    @Test
    void 훈련과_검증_seed는_겹칠_수_없다() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TuningConfig.validatePartitions(
                        List.of(201L, 202L),
                        List.of(202L, 301L)));
    }

    @Test
    void 잠긴_기존_holdout_seed는_튜닝에_쓸_수_없다() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TuningConfig.validatePartitions(
                        List.of(101L),
                        List.of(301L)));
    }

    private static TuningCase testCase() {
        return new TuningCase("fixture.json", "fixture-sha", 201L, null, null, null);
    }

    private static AlnsObservation observation(
            SearchConfig config,
            TuningCase tuningCase,
            int fairnessScore,
            long elapsedMillis) {
        return new AlnsObservation(
                "alns_run",
                "fixture",
                "fixed-evaluations",
                config.id(),
                tuningCase.dataset(),
                tuningCase.sha256(),
                tuningCase.seed(),
                RosterScore.of(0, 0, 0, fairnessScore, 0),
                true,
                elapsedMillis,
                10L,
                0L,
                "EVALUATION_LIMIT_REACHED",
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0.0d,
                0,
                List.of(),
                List.of(),
                null,
                null);
    }
}
