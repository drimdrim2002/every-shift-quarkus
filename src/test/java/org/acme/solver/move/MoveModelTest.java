package org.acme.solver.move;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.acme.solver.core.PlanningProblem;
import org.junit.jupiter.api.Test;

class MoveModelTest {

    @Test
    void reassignMove_apply와_rollback은_assignment_fingerprint_score를_복구한다() {
        Phase4TestSupport.Fixture fixture = Phase4TestSupport.fixture("request.json");
        int shift = Phase4TestSupport.mutableShift(fixture.problem());
        int employee = fixture.state().employeeIndex(shift);
        ReassignMove move = ReassignMove.create(
                fixture.problem(), fixture.state(), shift,
                Phase4TestSupport.differentEmployee(fixture.problem(), employee, 0));

        assertInverseRollback(fixture, move);
    }

    @Test
    void swapMove_apply와_rollback은_두_assignment를_복구한다() {
        Phase4TestSupport.Fixture fixture = Phase4TestSupport.fixture("request.json");
        int first = -1;
        int second = -1;
        for (int candidate : fixture.problem().mutableShiftIndexes()) {
            if (first < 0) {
                first = candidate;
            } else if (fixture.state().employeeIndex(first) != fixture.state().employeeIndex(candidate)) {
                second = candidate;
                break;
            }
        }
        SwapMove move = SwapMove.create(fixture.problem(), fixture.state(), first, second);

        assertInverseRollback(fixture, move);
    }

    @Test
    void chainMove_apply와_rollback은_모든_leg를_역순_복구한다() {
        Phase4TestSupport.Fixture fixture = Phase4TestSupport.fixture("request.json");
        int first = Phase4TestSupport.mutableShift(fixture.problem());
        int second = Phase4TestSupport.mutableShift(fixture.problem(), first);
        int third = Phase4TestSupport.mutableShift(fixture.problem(), first, second);
        ChainMove move = ChainMove.create(fixture.problem(), fixture.state(), List.of(
                new ChainMove.Leg(first, Phase4TestSupport.differentEmployee(
                        fixture.problem(), fixture.state().employeeIndex(first), 0)),
                new ChainMove.Leg(second, Phase4TestSupport.differentEmployee(
                        fixture.problem(), fixture.state().employeeIndex(second), 1)),
                new ChainMove.Leg(third, Phase4TestSupport.differentEmployee(
                        fixture.problem(), fixture.state().employeeIndex(third), 2))));

        assertInverseRollback(fixture, move);
    }

    @Test
    void relationGroupReassignMove는_완전한_group을_같은_실제일_교대에_원자적으로_배정한다() {
        Phase4TestSupport.Fixture fixture = Phase4TestSupport.fixture("preceptor.json");
        RelationGroupReassignMove move = findRelationMove(fixture);

        assertInverseRollback(fixture, move);
    }

    @Test
    void pinned_shift는_move_생성_단계에서_구조적으로_차단한다() {
        Phase4TestSupport.Fixture fixture = Phase4TestSupport.fixture("request.json");
        int pinned = fixture.problem().pinnedShiftIndexes()[0];
        int mutable = Phase4TestSupport.mutableShift(fixture.problem());
        int pinnedNewEmployee = Phase4TestSupport.differentEmployee(
                fixture.problem(), fixture.state().employeeIndex(pinned), 0);

        assertThrows(IllegalArgumentException.class, () -> ReassignMove.create(
                fixture.problem(), fixture.state(), pinned, pinnedNewEmployee));
        assertThrows(IllegalArgumentException.class, () -> SwapMove.create(
                fixture.problem(), fixture.state(), pinned, mutable));
        assertThrows(IllegalArgumentException.class, () -> ChainMove.create(
                fixture.problem(), fixture.state(), List.of(
                        new ChainMove.Leg(pinned, pinnedNewEmployee),
                        new ChainMove.Leg(mutable, Phase4TestSupport.differentEmployee(
                                fixture.problem(), fixture.state().employeeIndex(mutable), 1)))));
    }

    @Test
    void transaction_중간상태는_score_fingerprint_snapshot_assignment로_노출되지_않는다() {
        Phase4TestSupport.Fixture fixture = Phase4TestSupport.fixture("request.json");
        int shift = Phase4TestSupport.mutableShift(fixture.problem());
        ReassignMove move = ReassignMove.create(
                fixture.problem(), fixture.state(), shift,
                Phase4TestSupport.differentEmployee(
                        fixture.problem(), fixture.state().employeeIndex(shift), 0));

        try (MoveTransaction transaction = MoveTransaction.open(fixture.state(), fixture.incremental())) {
            transaction.apply(move);
            assertThrows(IllegalStateException.class, fixture.state()::score);
            assertThrows(IllegalStateException.class, fixture.state()::fingerprint);
            assertThrows(IllegalStateException.class, fixture.state()::snapshot);
            assertThrows(IllegalStateException.class, fixture.state()::assignments);
        }
    }

    private static void assertInverseRollback(Phase4TestSupport.Fixture fixture, Move move) {
        int[] baselineAssignments = fixture.state().assignments();
        SolutionFingerprint baselineFingerprint = fixture.state().fingerprint();
        var baselineScore = fixture.state().score();
        var baselineCache = fixture.incremental().cacheFingerprint();

        MoveTransaction transaction = MoveTransaction.open(fixture.state(), fixture.incremental());
        transaction.apply(move);
        transaction.verifyCandidate();
        assertEquals(fixture.initial(), fixture.incremental().lastVerifiedBest(),
                "transaction 내부 candidate는 verified best로 노출되면 안 됩니다.");
        transaction.rollback();

        assertEquals(MoveTransactionStatus.ROLLED_BACK, transaction.status());
        assertArrayEquals(baselineAssignments, fixture.state().assignments());
        assertEquals(baselineFingerprint, fixture.state().fingerprint());
        assertEquals(baselineScore, fixture.state().score());
        assertEquals(baselineCache, fixture.incremental().cacheFingerprint());
    }

    private static RelationGroupReassignMove findRelationMove(Phase4TestSupport.Fixture fixture) {
        PlanningProblem problem = fixture.problem();
        PreceptorRelationIndex relationIndex = new PreceptorRelationIndex(problem);
        Map<String, List<Integer>> shiftsBySlot = new LinkedHashMap<>();
        for (int shiftIndex : problem.mutableShiftIndexes()) {
            PlanningProblem.ShiftData shift = problem.shifts().get(shiftIndex);
            String key = shift.start().toLocalDate() + "\u0000" + shift.shiftCode();
            shiftsBySlot.computeIfAbsent(key, ignored -> new ArrayList<>()).add(shiftIndex);
        }

        for (List<Integer> group : relationIndex.groups().values()) {
            if (group.size() < 2) {
                continue;
            }
            for (List<Integer> slotShifts : shiftsBySlot.values()) {
                if (slotShifts.size() < group.size()) {
                    continue;
                }
                Map<Integer, Integer> assignment = matchWithoutNoOp(fixture, slotShifts, group);
                if (assignment != null) {
                    return RelationGroupReassignMove.create(problem, fixture.state(), assignment);
                }
            }
        }
        throw new IllegalStateException("테스트용 relation group move를 찾지 못했습니다.");
    }

    private static Map<Integer, Integer> matchWithoutNoOp(
            Phase4TestSupport.Fixture fixture, List<Integer> shifts, List<Integer> employees) {
        for (int rotation = 0; rotation < employees.size(); rotation++) {
            Map<Integer, Integer> result = new LinkedHashMap<>();
            boolean valid = true;
            for (int index = 0; index < employees.size(); index++) {
                int shift = shifts.get(index);
                int employee = employees.get((index + rotation) % employees.size());
                if (fixture.state().employeeIndex(shift) == employee) {
                    valid = false;
                    break;
                }
                result.put(shift, employee);
            }
            if (valid) {
                return result;
            }
        }
        return null;
    }
}
