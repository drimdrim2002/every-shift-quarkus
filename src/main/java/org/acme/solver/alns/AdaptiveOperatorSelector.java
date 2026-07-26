package org.acme.solver.alns;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 안정적인 list index와 단일 seeded RNG를 쓰는 독립 destroy/repair roulette 선택기입니다.
 */
public final class AdaptiveOperatorSelector {

    public record Selection(
            int destroyIndex,
            DestroyOperator destroyOperator,
            int repairIndex,
            RepairOperator repairOperator) {
    }

    private final List<DestroyOperator> destroyOperators;
    private final List<RepairOperator> repairOperators;
    private final int[][] compatibleRepairIndexes;
    private final AdaptiveOperatorConfig config;
    private final RandomGenerator random;
    private final FamilyState destroyState;
    private final FamilyState repairState;
    private int outcomesInSegment;
    private long completedSegments;

    public AdaptiveOperatorSelector(
            List<? extends DestroyOperator> destroyOperators,
            List<? extends RepairOperator> repairOperators,
            OperatorCompatibilityMatrix compatibilityMatrix,
            AdaptiveOperatorConfig config,
            RandomGenerator random) {
        Objects.requireNonNull(destroyOperators, "destroyOperators");
        Objects.requireNonNull(repairOperators, "repairOperators");
        Objects.requireNonNull(compatibilityMatrix, "compatibilityMatrix");
        this.config = Objects.requireNonNull(config, "config");
        this.random = Objects.requireNonNull(random, "random");
        if (destroyOperators.isEmpty() || repairOperators.isEmpty()) {
            throw new IllegalArgumentException("destroy와 repair operator는 각각 하나 이상이어야 합니다.");
        }
        this.destroyOperators = stableCopy(destroyOperators, "destroy");
        this.repairOperators = stableCopy(repairOperators, "repair");
        this.compatibleRepairIndexes = buildCompatibility(compatibilityMatrix);
        this.destroyState = new FamilyState(this.destroyOperators.size(), config.initialWeight());
        this.repairState = new FamilyState(this.repairOperators.size(), config.initialWeight());
    }

    public Selection select() {
        int destroyIndex = weightedIndex(destroyState.weights, allIndexes(destroyState.weights.length));
        int repairIndex = weightedIndex(repairState.weights, compatibleRepairIndexes[destroyIndex]);
        return new Selection(
                destroyIndex,
                destroyOperators.get(destroyIndex),
                repairIndex,
                repairOperators.get(repairIndex));
    }

    public void recordOutcome(Selection selection, OperatorOutcome outcome) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(outcome, "outcome");
        validateSelection(selection);
        double reward = config.reward(outcome);
        record(destroyState, selection.destroyIndex(), reward, outcome);
        record(repairState, selection.repairIndex(), reward, outcome);
        outcomesInSegment++;
        if (outcomesInSegment >= config.segmentLength()) {
            updateWeights();
        }
    }

    /** 마지막 미완성 segment도 같은 공식으로 반영합니다. */
    public void finishSegment() {
        if (outcomesInSegment > 0) {
            updateWeights();
        }
    }

    public double destroyWeight(String operatorId) {
        return destroyState.weights[indexOfDestroy(operatorId)];
    }

    public double repairWeight(String operatorId) {
        return repairState.weights[indexOfRepair(operatorId)];
    }

    public long completedSegments() {
        return completedSegments;
    }

    public List<OperatorStatistics> destroyStatistics() {
        return statistics(destroyOperators.stream().map(DestroyOperator::id).toList(), destroyState);
    }

    public List<OperatorStatistics> repairStatistics() {
        return statistics(repairOperators.stream().map(RepairOperator::id).toList(), repairState);
    }

    double destroySelectionProbability(String operatorId) {
        int index = indexOfDestroy(operatorId);
        return probability(destroyState.weights, allIndexes(destroyState.weights.length), index);
    }

    double repairSelectionProbability(String destroyOperatorId, String repairOperatorId) {
        int destroyIndex = indexOfDestroy(destroyOperatorId);
        int repairIndex = indexOfRepair(repairOperatorId);
        return probability(repairState.weights, compatibleRepairIndexes[destroyIndex], repairIndex);
    }

    private void updateWeights() {
        updateFamily(destroyState);
        updateFamily(repairState);
        outcomesInSegment = 0;
        completedSegments++;
    }

    private void updateFamily(FamilyState state) {
        for (int index = 0; index < state.weights.length; index++) {
            long useCount = state.segmentUseCounts[index];
            if (useCount > 0L) {
                double averageReward = state.segmentRewardSums[index] / (double) useCount;
                double updated = (1.0d - config.reactionFactor()) * state.weights[index]
                        + config.reactionFactor() * averageReward;
                updated = Math.max(config.minimumWeight(), updated);
                requirePositiveFinite(updated, "updatedWeight[" + index + "]");
                state.weights[index] = updated;
            }
            state.segmentRewardSums[index] = 0.0d;
            state.segmentUseCounts[index] = 0L;
        }
    }

    private void record(FamilyState state, int index, double reward, OperatorOutcome outcome) {
        double rewardSum = state.segmentRewardSums[index] + reward;
        if (!Double.isFinite(rewardSum) || rewardSum < 0.0d) {
            throw new IllegalStateException("operator rewardSum이 유한한 비음수가 아닙니다.");
        }
        state.segmentRewardSums[index] = rewardSum;
        state.segmentUseCounts[index]++;
        state.selectionCounts[index]++;
        switch (outcome) {
            case GLOBAL_BEST -> state.globalBestCounts[index]++;
            case CURRENT_IMPROVEMENT -> state.currentImprovementCounts[index]++;
            case ACCEPTED_WORSENING -> state.acceptedWorseningCounts[index]++;
            case REJECTED -> state.rejectionCounts[index]++;
        }
    }

    private int weightedIndex(double[] weights, int[] eligibleIndexes) {
        double sum = weightSum(weights, eligibleIndexes);
        double draw = random.nextDouble();
        if (!Double.isFinite(draw) || draw < 0.0d || draw >= 1.0d) {
            throw new IllegalStateException("operator RNG는 유한한 [0,1) 값을 반환해야 합니다.");
        }
        double target = draw * sum;
        double cumulative = 0.0d;
        for (int index : eligibleIndexes) {
            cumulative += weights[index];
            if (target < cumulative) {
                return index;
            }
        }
        return eligibleIndexes[eligibleIndexes.length - 1];
    }

    private static double probability(double[] weights, int[] eligibleIndexes, int targetIndex) {
        boolean eligible = false;
        for (int index : eligibleIndexes) {
            if (index == targetIndex) {
                eligible = true;
                break;
            }
        }
        if (!eligible) {
            return 0.0d;
        }
        return weights[targetIndex] / weightSum(weights, eligibleIndexes);
    }

    private static double weightSum(double[] weights, int[] eligibleIndexes) {
        double sum = 0.0d;
        for (int index : eligibleIndexes) {
            requirePositiveFinite(weights[index], "weight[" + index + "]");
            sum += weights[index];
        }
        requirePositiveFinite(sum, "weightSum");
        return sum;
    }

    private int[][] buildCompatibility(OperatorCompatibilityMatrix matrix) {
        int[][] result = new int[destroyOperators.size()][];
        for (int destroyIndex = 0; destroyIndex < destroyOperators.size(); destroyIndex++) {
            DestroyOperator destroy = destroyOperators.get(destroyIndex);
            int[] buffer = new int[repairOperators.size()];
            int count = 0;
            for (int repairIndex = 0; repairIndex < repairOperators.size(); repairIndex++) {
                if (matrix.isCompatible(destroy, repairOperators.get(repairIndex))) {
                    buffer[count++] = repairIndex;
                }
            }
            if (count == 0) {
                throw new IllegalArgumentException(
                        "compatible repair가 없는 destroy operator입니다: " + destroy.id());
            }
            result[destroyIndex] = Arrays.copyOf(buffer, count);
        }
        return result;
    }

    private void validateSelection(Selection selection) {
        if (selection.destroyIndex() < 0
                || selection.destroyIndex() >= destroyOperators.size()
                || selection.repairIndex() < 0
                || selection.repairIndex() >= repairOperators.size()
                || destroyOperators.get(selection.destroyIndex()) != selection.destroyOperator()
                || repairOperators.get(selection.repairIndex()) != selection.repairOperator()) {
            throw new IllegalArgumentException("다른 selector 또는 손상된 Selection입니다.");
        }
        boolean compatible = Arrays.stream(compatibleRepairIndexes[selection.destroyIndex()])
                .anyMatch(index -> index == selection.repairIndex());
        if (!compatible) {
            throw new IllegalArgumentException("Selection의 operator 조합이 호환되지 않습니다.");
        }
    }

    private int indexOfDestroy(String id) {
        for (int index = 0; index < destroyOperators.size(); index++) {
            if (destroyOperators.get(index).id().equals(id)) {
                return index;
            }
        }
        throw new IllegalArgumentException("알 수 없는 destroy operator ID입니다: " + id);
    }

    private int indexOfRepair(String id) {
        for (int index = 0; index < repairOperators.size(); index++) {
            if (repairOperators.get(index).id().equals(id)) {
                return index;
            }
        }
        throw new IllegalArgumentException("알 수 없는 repair operator ID입니다: " + id);
    }

    private static <T> List<T> stableCopy(List<? extends T> source, String family) {
        List<T> copy = new ArrayList<>(source.size());
        Set<String> ids = new HashSet<>();
        for (T operator : source) {
            Objects.requireNonNull(operator, family + "Operator");
            String id;
            if (operator instanceof DestroyOperator destroy) {
                id = destroy.id();
            } else if (operator instanceof RepairOperator repair) {
                id = repair.id();
            } else {
                throw new IllegalArgumentException("지원하지 않는 operator 타입입니다.");
            }
            if (id == null || id.isBlank() || !ids.add(id)) {
                throw new IllegalArgumentException(family + " operator ID는 비어 있거나 중복될 수 없습니다: " + id);
            }
            copy.add(operator);
        }
        return List.copyOf(copy);
    }

    private static List<OperatorStatistics> statistics(List<String> ids, FamilyState state) {
        List<OperatorStatistics> result = new ArrayList<>(ids.size());
        for (int index = 0; index < ids.size(); index++) {
            result.add(new OperatorStatistics(
                    index,
                    ids.get(index),
                    state.weights[index],
                    state.selectionCounts[index],
                    state.globalBestCounts[index],
                    state.currentImprovementCounts[index],
                    state.acceptedWorseningCounts[index],
                    state.rejectionCounts[index]));
        }
        return List.copyOf(result);
    }

    private static int[] allIndexes(int count) {
        int[] result = new int[count];
        for (int index = 0; index < count; index++) {
            result[index] = index;
        }
        return result;
    }

    private static void requirePositiveFinite(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0d) {
            throw new IllegalStateException(name + "은 유한한 양수여야 합니다: " + value);
        }
    }

    private static final class FamilyState {
        private final double[] weights;
        private final double[] segmentRewardSums;
        private final long[] segmentUseCounts;
        private final long[] selectionCounts;
        private final long[] globalBestCounts;
        private final long[] currentImprovementCounts;
        private final long[] acceptedWorseningCounts;
        private final long[] rejectionCounts;

        private FamilyState(int size, double initialWeight) {
            this.weights = new double[size];
            Arrays.fill(weights, initialWeight);
            this.segmentRewardSums = new double[size];
            this.segmentUseCounts = new long[size];
            this.selectionCounts = new long[size];
            this.globalBestCounts = new long[size];
            this.currentImprovementCounts = new long[size];
            this.acceptedWorseningCounts = new long[size];
            this.rejectionCounts = new long[size];
        }
    }
}
