package org.acme.solver.alns;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class RepairOperatorSupport {

    private RepairOperatorSupport() {
    }

    static boolean fillGreedy(RepairContext context) {
        while (!context.isComplete()) {
            int[] unassigned = context.unassignedShiftIndexes();
            if (unassigned.length == 0) {
                break;
            }
            int shiftIndex = unassigned[0];
            int[] candidates = context.candidatesByInsertionCost(shiftIndex);
            if (candidates.length == 0) {
                return false;
            }
            context.assign(shiftIndex, randomBestTie(context, shiftIndex, candidates));
        }
        return context.isComplete();
    }

    static int randomBestTie(RepairContext context, int shiftIndex, int[] rankedCandidates) {
        long bestCost = context.insertionCost(shiftIndex, rankedCandidates[0]);
        int tieCount = 1;
        while (tieCount < rankedCandidates.length
                && context.insertionCost(shiftIndex, rankedCandidates[tieCount]) == bestCost) {
            tieCount++;
        }
        return rankedCandidates[tieCount == 1 ? 0 : context.random().nextInt(tieCount)];
    }

    static Choice highestRegret2(RepairContext context) {
        List<Choice> choices = new ArrayList<>();
        for (int shiftIndex : context.unassignedShiftIndexes()) {
            int[] candidates = context.candidatesByInsertionCost(shiftIndex);
            if (candidates.length == 0) {
                continue;
            }
            long bestCost = context.insertionCost(shiftIndex, candidates[0]);
            long regret = candidates.length == 1
                    ? Long.MAX_VALUE
                    : subtractSaturated(context.insertionCost(shiftIndex, candidates[1]), bestCost);
            choices.add(new Choice(shiftIndex, candidates, regret));
        }
        return choices.stream()
                .max(Comparator
                        .comparingLong(Choice::regret)
                        .thenComparing((Choice choice) -> -choice.shiftIndex()))
                .orElse(null);
    }

    private static long subtractSaturated(long left, long right) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    record Choice(int shiftIndex, int[] rankedCandidates, long regret) {
    }
}
