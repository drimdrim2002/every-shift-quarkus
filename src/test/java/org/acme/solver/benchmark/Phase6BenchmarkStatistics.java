package org.acme.solver.benchmark;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.acme.solver.core.RosterScore;

/** Phase 6 benchmark 보고서가 사용하는 결정론적 nearest-rank 통계 함수입니다. */
final class Phase6BenchmarkStatistics {

    private Phase6BenchmarkStatistics() {
    }

    static long nearestRankLong(List<Long> values, double quantile) {
        requireValues(values);
        requireQuantile(quantile);
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get(nearestRankIndex(sorted.size(), quantile));
    }

    static RosterScore nearestRankScore(List<RosterScore> values, double quantile) {
        requireValues(values);
        requireQuantile(quantile);
        List<RosterScore> sorted = values.stream().sorted().toList();
        return sorted.get(nearestRankIndex(sorted.size(), quantile));
    }

    static long[] coordinateQuantile(List<RosterScore> values, double quantile) {
        requireValues(values);
        requireQuantile(quantile);
        long[] result = new long[1 + RosterScore.SOFT_LEVELS];
        result[0] = nearestRankLong(values.stream()
                .map(score -> (long) score.hardScore())
                .toList(), quantile);
        for (int level = 0; level < RosterScore.SOFT_LEVELS; level++) {
            int scoreLevel = level;
            result[level + 1] = nearestRankLong(values.stream()
                    .map(score -> (long) score.softScore(scoreLevel))
                    .toList(), quantile);
        }
        return result;
    }

    static long[] pairedDeltaQuantile(
            List<ScorePair> pairs,
            double quantile) {
        requireValues(pairs);
        requireQuantile(quantile);
        long[] result = new long[1 + RosterScore.SOFT_LEVELS];
        result[0] = nearestRankLong(pairs.stream()
                .map(pair -> pair.candidate().hardDeltaFrom(pair.baseline()))
                .toList(), quantile);
        for (int level = 0; level < RosterScore.SOFT_LEVELS; level++) {
            int scoreLevel = level;
            result[level + 1] = nearestRankLong(pairs.stream()
                    .map(pair -> pair.candidate().softDeltaFrom(pair.baseline(), scoreLevel))
                    .toList(), quantile);
        }
        return result;
    }

    static WinTieLoss winTieLoss(List<ScorePair> pairs) {
        requireValues(pairs);
        long wins = 0L;
        long ties = 0L;
        long losses = 0L;
        for (ScorePair pair : pairs) {
            int comparison = pair.candidate().compareTo(pair.baseline());
            if (comparison > 0) {
                wins++;
            } else if (comparison < 0) {
                losses++;
            } else {
                ties++;
            }
        }
        return new WinTieLoss(wins, ties, losses);
    }

    static List<String> stableGroupOrder(List<String> values) {
        Objects.requireNonNull(values, "values");
        List<String> result = new ArrayList<>(values.stream().distinct().toList());
        result.sort(Comparator.naturalOrder());
        return List.copyOf(result);
    }

    private static int nearestRankIndex(int size, double quantile) {
        int rank = (int) Math.ceil(quantile * size);
        return Math.max(0, Math.min(size - 1, rank - 1));
    }

    private static void requireQuantile(double quantile) {
        if (!Double.isFinite(quantile) || quantile <= 0.0d || quantile > 1.0d) {
            throw new IllegalArgumentException("quantile은 0 초과 1 이하여야 합니다: " + quantile);
        }
    }

    private static void requireValues(List<?> values) {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty() || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("통계 입력은 null 없는 비어 있지 않은 목록이어야 합니다.");
        }
    }

    record ScorePair(RosterScore baseline, RosterScore candidate) {
        ScorePair {
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(candidate, "candidate");
        }
    }

    record WinTieLoss(long wins, long ties, long losses) {
        long total() {
            return wins + ties + losses;
        }
    }
}
