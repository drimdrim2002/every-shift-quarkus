package org.acme.solver.benchmark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.acme.solver.benchmark.Phase6BenchmarkStatistics.ScorePair;
import org.acme.solver.core.RosterScore;
import org.junit.jupiter.api.Test;

class Phase6BenchmarkStatisticsTest {

    @Test
    void nearestRank는_보간하지_않고_p10_median_p90을_재현한다() {
        List<Long> values = List.of(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L);

        assertEquals(10L, Phase6BenchmarkStatistics.nearestRankLong(values, 0.10d));
        assertEquals(50L, Phase6BenchmarkStatistics.nearestRankLong(values, 0.50d));
        assertEquals(90L, Phase6BenchmarkStatistics.nearestRankLong(values, 0.90d));
        assertEquals(100L, Phase6BenchmarkStatistics.nearestRankLong(values, 0.95d));
    }

    @Test
    void score_quantile은_사전식_전체_vector를_정렬한다() {
        RosterScore hardLoss = RosterScore.of(-1, 0, 0, 0, 0);
        RosterScore lowerSoft = RosterScore.of(0, -1, 10_000, 10_000, 10_000);
        RosterScore higherSoft = RosterScore.of(0, 0, -10_000, -10_000, -10_000);

        assertEquals(hardLoss, Phase6BenchmarkStatistics.nearestRankScore(
                List.of(higherSoft, hardLoss, lowerSoft), 0.10d));
        assertEquals(lowerSoft, Phase6BenchmarkStatistics.nearestRankScore(
                List.of(higherSoft, hardLoss, lowerSoft), 0.50d));
        assertEquals(higherSoft, Phase6BenchmarkStatistics.nearestRankScore(
                List.of(higherSoft, hardLoss, lowerSoft), 0.90d));
    }

    @Test
    void paired_delta와_win_tie_loss는_candidate_minus_baseline으로_계산한다() {
        RosterScore baseline = RosterScore.of(0, 0, 0, -100, 0);
        List<ScorePair> pairs = List.of(
                new ScorePair(baseline, RosterScore.of(0, 0, 0, -90, 0)),
                new ScorePair(baseline, baseline),
                new ScorePair(baseline, RosterScore.of(0, 0, 0, -110, 0)));

        assertEquals(new Phase6BenchmarkStatistics.WinTieLoss(1, 1, 1),
                Phase6BenchmarkStatistics.winTieLoss(pairs));
        assertArrayEquals(new long[] { 0L, 0L, 0L, 0L, 0L },
                Phase6BenchmarkStatistics.pairedDeltaQuantile(pairs, 0.50d));
    }

    @Test
    void 빈_입력과_잘못된_quantile을_거절한다() {
        assertThrows(IllegalArgumentException.class,
                () -> Phase6BenchmarkStatistics.nearestRankLong(List.of(), 0.50d));
        assertThrows(IllegalArgumentException.class,
                () -> Phase6BenchmarkStatistics.nearestRankLong(List.of(1L), 0.0d));
        assertThrows(IllegalArgumentException.class,
                () -> Phase6BenchmarkStatistics.nearestRankLong(List.of(1L), 1.1d));
    }
}
