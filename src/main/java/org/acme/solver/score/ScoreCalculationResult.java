package org.acme.solver.score;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.acme.solver.core.RosterScore;

/**
 * 전체 점수와 constraint/영향 대상 단위 breakdown입니다.
 */
public final class ScoreCalculationResult {

    private final RosterScore score;
    private final List<ConstraintContribution> contributions;
    private final Map<String, Integer> contributionByConstraintId;
    private final Map<String, RosterScore> scoreByConstraintId;

    ScoreCalculationResult(List<ConstraintContribution> contributions) {
        this.contributions = List.copyOf(Objects.requireNonNull(contributions, "contributions"));

        long hard = 0L;
        long[] soft = new long[RosterScore.SOFT_LEVELS];
        Map<String, Long> totals = new LinkedHashMap<>();
        Map<String, long[]> vectors = new LinkedHashMap<>();
        for (ConstraintContribution contribution : this.contributions) {
            if (contribution.level().isHard()) {
                hard = Math.addExact(hard, contribution.contribution());
            } else {
                int softIndex = contribution.level().softIndex();
                soft[softIndex] = Math.addExact(soft[softIndex], contribution.contribution());
            }
            totals.merge(contribution.constraintId(), (long) contribution.contribution(), Math::addExact);
            long[] vector = vectors.computeIfAbsent(contribution.constraintId(), ignored -> new long[5]);
            int vectorIndex = contribution.level().isHard() ? 0 : contribution.level().softIndex() + 1;
            vector[vectorIndex] = Math.addExact(vector[vectorIndex], contribution.contribution());
        }

        int[] intSoft = new int[RosterScore.SOFT_LEVELS];
        for (int index = 0; index < intSoft.length; index++) {
            intSoft[index] = Math.toIntExact(soft[index]);
        }
        this.score = new RosterScore(Math.toIntExact(hard), intSoft);

        Map<String, Integer> intTotals = new LinkedHashMap<>();
        totals.forEach((constraintId, total) -> intTotals.put(constraintId, Math.toIntExact(total)));
        this.contributionByConstraintId = Collections.unmodifiableMap(intTotals);

        Map<String, RosterScore> constraintScores = new LinkedHashMap<>();
        vectors.forEach((constraintId, vector) -> constraintScores.put(
                constraintId,
                RosterScore.of(
                        Math.toIntExact(vector[0]),
                        Math.toIntExact(vector[1]),
                        Math.toIntExact(vector[2]),
                        Math.toIntExact(vector[3]),
                        Math.toIntExact(vector[4]))));
        this.scoreByConstraintId = Collections.unmodifiableMap(constraintScores);
    }

    public RosterScore score() {
        return score;
    }

    public List<ConstraintContribution> contributions() {
        return contributions;
    }

    public Map<String, Integer> contributionByConstraintId() {
        return contributionByConstraintId;
    }

    public Map<String, RosterScore> scoreByConstraintId() {
        return scoreByConstraintId;
    }

    public List<ConstraintContribution> contributions(String constraintId) {
        Objects.requireNonNull(constraintId, "constraintId");
        List<ConstraintContribution> matches = new ArrayList<>();
        for (ConstraintContribution contribution : contributions) {
            if (constraintId.equals(contribution.constraintId())) {
                matches.add(contribution);
            }
        }
        return List.copyOf(matches);
    }
}
