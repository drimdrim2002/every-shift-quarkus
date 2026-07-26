package org.acme.solver.lahc;

import java.util.Optional;
import java.util.SplittableRandom;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.move.Move;
import org.acme.solver.move.ReassignMove;
import org.acme.solver.move.SearchState;
import org.acme.solver.move.SwapMove;

/**
 * Change/Swap union을 POJO transaction move로 구현한 selector입니다.
 *
 * <p>skill, availability, rest, preceptor 관계를 사전 필터하지 않습니다. 전체 직원 범위처럼
 * 모든 employee value를 후보로 만들고, exact score와 Late Acceptance가 수락 여부를 결정합니다.
 * pinned shift와 명백한 no-op만 여기서 제외합니다.</p>
 */
public final class OptaStyleMoveSelector implements LahcMoveSelector {

    private static final int RANDOM_ATTEMPTS = 64;

    @Override
    public Optional<Move> select(
            PlanningProblem problem, SearchState state, SplittableRandom random) {
        int[] mutableShifts = problem.mutableShiftIndexes();
        if (mutableShifts.length == 0 || problem.employeeCount() < 2) {
            return Optional.empty();
        }

        for (int attempt = 0; attempt < RANDOM_ATTEMPTS; attempt++) {
            Optional<Move> candidate = random.nextBoolean()
                    ? randomChange(problem, state, random, mutableShifts)
                    : randomSwap(problem, state, random, mutableShifts);
            if (candidate.isPresent()) {
                return candidate;
            }
        }

        Optional<Move> change = firstChange(problem, state, mutableShifts);
        return change.isPresent() ? change : firstSwap(problem, state, mutableShifts);
    }

    private static Optional<Move> randomChange(
            PlanningProblem problem,
            SearchState state,
            SplittableRandom random,
            int[] mutableShifts) {
        int shiftIndex = mutableShifts[random.nextInt(mutableShifts.length)];
        int oldEmployee = state.employeeIndex(shiftIndex);
        if (oldEmployee < 0) {
            return Optional.empty();
        }
        int sampled = random.nextInt(problem.employeeCount() - 1);
        int newEmployee = sampled >= oldEmployee ? sampled + 1 : sampled;
        return Optional.of(ReassignMove.create(problem, state, shiftIndex, newEmployee));
    }

    private static Optional<Move> randomSwap(
            PlanningProblem problem,
            SearchState state,
            SplittableRandom random,
            int[] mutableShifts) {
        if (mutableShifts.length < 2) {
            return Optional.empty();
        }
        int firstShiftIndex = mutableShifts[random.nextInt(mutableShifts.length)];
        int secondShiftIndex = mutableShifts[random.nextInt(mutableShifts.length)];
        if (firstShiftIndex == secondShiftIndex
                || state.employeeIndex(firstShiftIndex) == state.employeeIndex(secondShiftIndex)) {
            return Optional.empty();
        }
        return Optional.of(SwapMove.create(problem, state, firstShiftIndex, secondShiftIndex));
    }

    private static Optional<Move> firstChange(
            PlanningProblem problem,
            SearchState state,
            int[] mutableShifts) {
        for (int shiftIndex : mutableShifts) {
            int oldEmployee = state.employeeIndex(shiftIndex);
            if (oldEmployee < 0) {
                continue;
            }
            int newEmployee = oldEmployee == 0 ? 1 : 0;
            return Optional.of(ReassignMove.create(problem, state, shiftIndex, newEmployee));
        }
        return Optional.empty();
    }

    private static Optional<Move> firstSwap(
            PlanningProblem problem,
            SearchState state,
            int[] mutableShifts) {
        for (int left = 0; left < mutableShifts.length; left++) {
            int firstShiftIndex = mutableShifts[left];
            for (int right = left + 1; right < mutableShifts.length; right++) {
                int secondShiftIndex = mutableShifts[right];
                if (state.employeeIndex(firstShiftIndex) != state.employeeIndex(secondShiftIndex)) {
                    return Optional.of(SwapMove.create(problem, state, firstShiftIndex, secondShiftIndex));
                }
            }
        }
        return Optional.empty();
    }
}
