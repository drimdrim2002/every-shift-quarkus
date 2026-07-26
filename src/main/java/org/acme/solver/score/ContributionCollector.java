package org.acme.solver.score;

import java.util.ArrayList;
import java.util.List;

public final class ContributionCollector {

    private final List<ConstraintContribution> contributions = new ArrayList<>();

    public void penalize(String constraintId, ScoreLevel level, long magnitude,
            List<Integer> employeeIndexes, List<Integer> shiftIndexes) {
        if (magnitude < 0L) {
            throw new IllegalArgumentException("penalty magnitude는 음수일 수 없습니다: " + magnitude);
        }
        if (magnitude != 0L) {
            add(constraintId, level, Math.negateExact(Math.toIntExact(magnitude)), employeeIndexes, shiftIndexes);
        }
    }

    public void reward(String constraintId, ScoreLevel level, long magnitude,
            List<Integer> employeeIndexes, List<Integer> shiftIndexes) {
        if (magnitude < 0L) {
            throw new IllegalArgumentException("reward magnitude는 음수일 수 없습니다: " + magnitude);
        }
        if (magnitude != 0L) {
            add(constraintId, level, Math.toIntExact(magnitude), employeeIndexes, shiftIndexes);
        }
    }

    private void add(String constraintId, ScoreLevel level, int contribution,
            List<Integer> employeeIndexes, List<Integer> shiftIndexes) {
        contributions.add(new ConstraintContribution(
                constraintId, level, contribution, employeeIndexes, shiftIndexes));
    }

    public ScoreCalculationResult toResult() {
        return new ScoreCalculationResult(contributions);
    }
}
