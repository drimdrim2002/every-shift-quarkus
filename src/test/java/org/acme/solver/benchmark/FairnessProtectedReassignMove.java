package org.acme.solver.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.move.AssignmentChange;
import org.acme.solver.move.Move;
import org.acme.solver.move.ReassignMove;
import org.acme.solver.move.SearchState;

/**
 * fairness atomic witness 진단 전용의 단일 재배정 후보입니다.
 *
 * <p>후보는 모든 mutable shift와 현재 담당자가 아닌 모든 직원을 결정론적으로 열거합니다.
 * 보호 조건은 적용 뒤 full score를 확인한 뒤에만 판정합니다. 따라서 특정 Opta 해의 diff를 복사하지 않습니다.</p>
 */
final class FairnessProtectedReassignMove implements Move {

    private final ReassignMove delegate;

    private FairnessProtectedReassignMove(ReassignMove delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    static FairnessProtectedReassignMove create(
            PlanningProblem problem, SearchState state, int shiftIndex, int newEmployeeIndex) {
        return new FairnessProtectedReassignMove(ReassignMove.create(problem, state, shiftIndex, newEmployeeIndex));
    }

    static List<FairnessProtectedReassignMove> enumerate(PlanningProblem problem, SearchState state) {
        List<FairnessProtectedReassignMove> candidates = new ArrayList<>();
        for (int shiftIndex : problem.mutableShiftIndexes()) {
            int currentEmployee = state.employeeIndex(shiftIndex);
            for (int employeeIndex = 0; employeeIndex < problem.employeeCount(); employeeIndex++) {
                if (employeeIndex != currentEmployee) {
                    candidates.add(create(problem, state, shiftIndex, employeeIndex));
                }
            }
        }
        return List.copyOf(candidates);
    }

    /** hard·soft[0](undesired)를 보존하고 soft[1](fairness)만 엄격히 개선하는 선택 계약입니다. */
    static boolean isProtectedFairnessWitness(RosterScore before, RosterScore after) {
        return after.hardScore() >= before.hardScore()
                && after.softScore(0) >= before.softScore(0)
                && after.softScore(1) > before.softScore(1);
    }

    @Override
    public String moveType() {
        return "FAIRNESS_PROTECTED_REASSIGN";
    }

    @Override
    public List<AssignmentChange> changes() {
        return delegate.changes();
    }

    @Override
    public void apply(SearchState state) {
        delegate.apply(state);
    }

    @Override
    public void undo(SearchState state) {
        delegate.undo(state);
    }
}
