package org.acme.solver.alns;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 기존 지역 안전 비용에 정확한 soft[2] 삽입 한계 비용을 더한 regret-2 repair입니다.
 */
public final class FairnessAwareRegret2Repair implements RepairOperator {

    public static final String ID = "FAIRNESS_AWARE_REGRET_2_REPAIR";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RepairResult repair(RepairContext context) {
        while (context.beginAttempt()) {
            boolean failed = false;
            while (!context.isComplete()) {
                Choice choice = highestRegret2(context);
                if (choice == null) {
                    failed = true;
                    break;
                }
                context.assign(
                        choice.shiftIndex(),
                        randomBestTie(context, choice.shiftIndex(), choice.rankedCandidates()));
            }
            if (!failed && context.isComplete()) {
                return RepairResult.completed(context.attempts());
            }
        }
        return RepairResult.failed(context.attempts());
    }

    private static Choice highestRegret2(RepairContext context) {
        List<Choice> choices = new ArrayList<>();
        for (int shiftIndex : context.unassignedShiftIndexes()) {
            int[] candidates = candidatesByFairnessAwareCost(context, shiftIndex);
            if (candidates.length == 0) {
                continue;
            }
            long bestCost = context.fairnessAwareInsertionCost(shiftIndex, candidates[0]);
            long regret = candidates.length == 1
                    ? Long.MAX_VALUE
                    : subtractSaturated(
                            context.fairnessAwareInsertionCost(shiftIndex, candidates[1]),
                            bestCost);
            choices.add(new Choice(shiftIndex, candidates, regret));
        }
        return choices.stream()
                .max(Comparator
                        .comparingLong(Choice::regret)
                        .thenComparing((Choice choice) -> -choice.shiftIndex()))
                .orElse(null);
    }

    private static int[] candidatesByFairnessAwareCost(RepairContext context, int shiftIndex) {
        return Arrays.stream(context.candidateEmployeeIndexes(shiftIndex))
                .boxed()
                .sorted(Comparator
                        .comparingLong((Integer employeeIndex) ->
                                context.fairnessAwareInsertionCost(shiftIndex, employeeIndex))
                        .thenComparingInt(Integer::intValue))
                .mapToInt(Integer::intValue)
                .toArray();
    }

    private static int randomBestTie(
            RepairContext context,
            int shiftIndex,
            int[] rankedCandidates) {
        long bestCost = context.fairnessAwareInsertionCost(shiftIndex, rankedCandidates[0]);
        int tieCount = 1;
        while (tieCount < rankedCandidates.length
                && context.fairnessAwareInsertionCost(shiftIndex, rankedCandidates[tieCount]) == bestCost) {
            tieCount++;
        }
        return rankedCandidates[tieCount == 1 ? 0 : context.random().nextInt(tieCount)];
    }

    private static long subtractSaturated(long left, long right) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private record Choice(int shiftIndex, int[] rankedCandidates, long regret) {
    }
}
