package org.acme.solver.move;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.score.ScoreCacheFingerprint;
import org.junit.jupiter.api.Test;

class MoveTransactionPropertyTest {

    private static final long SEED = 20_260_715L;
    private static final int MOVE_COUNT = 10_000;

    @Test
    void seeded_10000회_random_move_apply_undo에서_assignment_fingerprint_score_불일치가_없다() {
        Phase4TestSupport.Fixture fixture = Phase4TestSupport.fixture("preceptor.json");
        Random random = new Random(SEED);
        Map<String, Integer> moveTypeCounts = new LinkedHashMap<>();
        int appliedMoveCount = 0;
        int assignmentMismatch = 0;
        int fingerprintMismatch = 0;
        int scoreMismatch = 0;
        int cacheMismatch = 0;

        while (appliedMoveCount < MOVE_COUNT) {
            int[] baselineAssignments = fixture.state().assignments();
            SolutionFingerprint baselineFingerprint = fixture.state().fingerprint();
            RosterScore baselineScore = fixture.state().score();
            ScoreCacheFingerprint baselineCache = fixture.incremental().cacheFingerprint();
            MoveTransaction transaction = MoveTransaction.open(fixture.state(), fixture.incremental());
            int movesInTransaction = Math.min(1 + random.nextInt(3), MOVE_COUNT - appliedMoveCount);

            for (int index = 0; index < movesInTransaction; index++) {
                Move move = randomMove(fixture, random);
                moveTypeCounts.merge(move.moveType(), 1, Integer::sum);
                transaction.apply(move);
                RosterSolution verified = transaction.verifyCandidate();
                RosterSolution internal = fixture.state().internalSnapshot();
                if (!java.util.Arrays.equals(
                        verified.employeeIndexByShift(), internal.employeeIndexByShift())) {
                    assignmentMismatch++;
                }
                if (!SolutionFingerprint.from(verified)
                        .equals(fixture.state().internalFingerprint())) {
                    fingerprintMismatch++;
                }
                if (!verified.score().equals(transaction.candidateScore())) {
                    scoreMismatch++;
                }
                appliedMoveCount++;
            }

            if (random.nextBoolean()) {
                transaction.commit();
            } else {
                transaction.rollback();
                if (!java.util.Arrays.equals(baselineAssignments, fixture.state().assignments())) {
                    assignmentMismatch++;
                }
                if (!baselineFingerprint.equals(fixture.state().fingerprint())) {
                    fingerprintMismatch++;
                }
                if (!baselineScore.equals(fixture.state().score())) {
                    scoreMismatch++;
                }
                if (!baselineCache.equals(fixture.incremental().cacheFingerprint())) {
                    cacheMismatch++;
                }
            }

            RosterScore full = fixture.full().calculateScore(
                    fixture.problem(), fixture.state().snapshot());
            if (!full.equals(fixture.state().score())) {
                scoreMismatch++;
            }
            if (!SolutionFingerprint.from(fixture.state().snapshot())
                    .equals(fixture.state().fingerprint())) {
                fingerprintMismatch++;
            }
        }

        System.out.printf(
                "PHASE4_PROPERTY seed=%d moves=%d types=%s assignmentMismatch=%d fingerprintMismatch=%d scoreMismatch=%d cacheMismatch=%d%n",
                SEED, appliedMoveCount, moveTypeCounts,
                assignmentMismatch, fingerprintMismatch, scoreMismatch, cacheMismatch);
        assertEquals(0, assignmentMismatch, "assignment mismatch");
        assertEquals(0, fingerprintMismatch, "fingerprint mismatch");
        assertEquals(0, scoreMismatch, "full/incremental mismatch");
        assertEquals(0, cacheMismatch, "score cache pollution");
        assertEquals(Set.of("REASSIGN", "SWAP", "CHAIN", "RELATION_GROUP_REASSIGN"),
                moveTypeCounts.keySet(), "모든 move 유형을 property test에서 실행해야 합니다.");
    }

    private static Move randomMove(Phase4TestSupport.Fixture fixture, Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> randomReassign(fixture, random);
            case 1 -> randomSwap(fixture, random);
            case 2 -> randomChain(fixture, random);
            default -> randomRelationGroup(fixture, random);
        };
    }

    private static ReassignMove randomReassign(
            Phase4TestSupport.Fixture fixture, Random random) {
        int[] mutable = fixture.problem().mutableShiftIndexes();
        int shift = mutable[random.nextInt(mutable.length)];
        int oldEmployee = fixture.state().employeeIndex(shift);
        int offset = 1 + random.nextInt(fixture.problem().employeeCount() - 1);
        int newEmployee = (oldEmployee + offset) % fixture.problem().employeeCount();
        return ReassignMove.create(fixture.problem(), fixture.state(), shift, newEmployee);
    }

    private static Move randomSwap(Phase4TestSupport.Fixture fixture, Random random) {
        int[] mutable = fixture.problem().mutableShiftIndexes();
        for (int attempt = 0; attempt < 20; attempt++) {
            int first = mutable[random.nextInt(mutable.length)];
            int second = mutable[random.nextInt(mutable.length)];
            if (first != second
                    && fixture.state().employeeIndex(first) != fixture.state().employeeIndex(second)) {
                return SwapMove.create(fixture.problem(), fixture.state(), first, second);
            }
        }
        return randomReassign(fixture, random);
    }

    private static ChainMove randomChain(
            Phase4TestSupport.Fixture fixture, Random random) {
        int[] mutable = fixture.problem().mutableShiftIndexes();
        int length = 2 + random.nextInt(3);
        Set<Integer> selected = new HashSet<>();
        List<ChainMove.Leg> legs = new ArrayList<>(length);
        while (legs.size() < length) {
            int shift = mutable[random.nextInt(mutable.length)];
            if (!selected.add(shift)) {
                continue;
            }
            int oldEmployee = fixture.state().employeeIndex(shift);
            int offset = 1 + random.nextInt(fixture.problem().employeeCount() - 1);
            legs.add(new ChainMove.Leg(
                    shift, (oldEmployee + offset) % fixture.problem().employeeCount()));
        }
        return ChainMove.create(fixture.problem(), fixture.state(), legs);
    }

    private static Move randomRelationGroup(
            Phase4TestSupport.Fixture fixture, Random random) {
        PreceptorRelationIndex relationIndex = new PreceptorRelationIndex(fixture.problem());
        List<List<Integer>> relationGroups = relationIndex.groups().values().stream()
                .filter(group -> group.size() >= 2)
                .toList();
        if (relationGroups.isEmpty()) {
            return randomReassign(fixture, random);
        }
        Map<String, List<Integer>> bySlot = new LinkedHashMap<>();
        for (int shiftIndex : fixture.problem().mutableShiftIndexes()) {
            var shift = fixture.problem().shifts().get(shiftIndex);
            String key = shift.start().toLocalDate() + "\u0000" + shift.shiftCode();
            bySlot.computeIfAbsent(key, ignored -> new ArrayList<>()).add(shiftIndex);
        }
        List<List<Integer>> slots = new ArrayList<>(bySlot.values());
        int groupStart = random.nextInt(relationGroups.size());
        int slotStart = random.nextInt(slots.size());
        for (int groupOffset = 0; groupOffset < relationGroups.size(); groupOffset++) {
            List<Integer> group = relationGroups.get((groupStart + groupOffset) % relationGroups.size());
            for (int slotOffset = 0; slotOffset < slots.size(); slotOffset++) {
                List<Integer> shifts = slots.get((slotStart + slotOffset) % slots.size());
                if (shifts.size() < group.size()) {
                    continue;
                }
                for (int rotation = 0; rotation < group.size(); rotation++) {
                    Map<Integer, Integer> assignment = new LinkedHashMap<>();
                    boolean valid = true;
                    for (int index = 0; index < group.size(); index++) {
                        int shift = shifts.get(index);
                        int employee = group.get((index + rotation) % group.size());
                        if (fixture.state().employeeIndex(shift) == employee) {
                            valid = false;
                            break;
                        }
                        assignment.put(shift, employee);
                    }
                    if (valid) {
                        return RelationGroupReassignMove.create(
                                fixture.problem(), fixture.state(), assignment);
                    }
                }
            }
        }
        return randomReassign(fixture, random);
    }
}
