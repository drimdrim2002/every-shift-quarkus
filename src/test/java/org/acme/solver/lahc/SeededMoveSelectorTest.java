package org.acme.solver.lahc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.EmployeeScheduleBuilder;
import org.acme.solver.adapter.PlanningProblemMapper;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.initial.InitialSolutionBuilder;
import org.acme.solver.move.AssignmentChange;
import org.acme.solver.move.Move;
import org.acme.solver.move.MoveCancelledException;
import org.acme.solver.move.MoveTransaction;
import org.acme.solver.move.MoveTransactionStatus;
import org.acme.solver.move.PreceptorRelationIndex;
import org.acme.solver.move.RelationGroupExchangeMove;
import org.acme.solver.move.SearchState;
import org.acme.solver.move.SolutionFingerprint;
import org.acme.solver.score.FullScoreCalculator;
import org.acme.solver.score.IncrementalScoreCalculator;
import org.acme.solver.score.ScoreCacheFingerprint;
import org.acme.test.JsonLoader;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class SeededMoveSelectorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final RosterScore PREVIOUS_PRECEPTOR_10K =
            RosterScore.of(0, -960, -3840, -6163, 0);

    @Test
    void selector_10000회는_cross_slot_relation_move를_생성하고_fixed_seed_sequence가_재현된다()
            throws Exception {
        Fixture first = preceptorFixture();
        Fixture second = preceptorFixture();
        SeededMoveSelector firstSelector = new SeededMoveSelector();
        SeededMoveSelector secondSelector = new SeededMoveSelector();
        SplittableRandom firstRandom = new SplittableRandom(42L);
        SplittableRandom secondRandom = new SplittableRandom(42L);
        FullScoreCalculator full = new FullScoreCalculator();
        Map<String, Integer> moveCounts = new LinkedHashMap<>();
        int relationMoves = 0;
        int scoreChangingRelationMoves = 0;

        for (int attempt = 0; attempt < 10_000; attempt++) {
            Move firstMove = firstSelector.select(first.problem(), first.state(), firstRandom).orElseThrow();
            Move secondMove = secondSelector.select(second.problem(), second.state(), secondRandom).orElseThrow();
            assertEquals(signature(firstMove), signature(secondMove));
            moveCounts.merge(firstMove.moveType(), 1, Integer::sum);
            if (!"RELATION_GROUP_EXCHANGE".equals(firstMove.moveType())) {
                continue;
            }

            relationMoves++;
            assertCrossSlot(first.problem(), firstMove);
            assertTrue(firstMove.changes().stream()
                    .allMatch(change -> change.oldEmployeeIndex() != change.newEmployeeIndex()));
            int[] candidateAssignments = first.initial().employeeIndexByShift();
            firstMove.changes().forEach(change ->
                    candidateAssignments[change.shiftIndex()] = change.newEmployeeIndex());
            RosterSolution candidate = new RosterSolution(
                    first.problem().employeeCount(), candidateAssignments, first.initial().score());
            if (!full.calculateScore(first.problem(), candidate).equals(first.initial().score())) {
                scoreChangingRelationMoves++;
            }
        }

        assertTrue(relationMoves > 0, "고정 seed 표본에서 relation move가 생성되어야 합니다.");
        assertTrue(relationMoves < 4_000,
                "relation move가 평가 예산을 과점하면 안 됩니다: " + relationMoves);
        assertTrue(scoreChangingRelationMoves > 0,
                "relation move 중 실제 점수를 변경하는 후보가 존재해야 합니다.");
        System.out.printf(
                "PHASE5_RELATION_SELECTOR moves=10000 types=%s relation=%d scoreChanging=%d validRatio=%.4f%n",
                moveCounts, relationMoves, scoreChangingRelationMoves,
                (double) scoreChangingRelationMoves / relationMoves);
    }

    @Test
    void 세명_relation_group과_target_singleton은_한_move로_교환되어_commit된다() {
        ExchangeFixture fixture = exchangeFixture(3, false, false, false);
        RelationGroupExchangeMove move = exchangeMove(fixture);

        MoveTransaction transaction = MoveTransaction.open(fixture.state(), fixture.incremental());
        transaction.apply(move);
        RosterSolution verified = transaction.verifyCandidate();
        transaction.commit();

        assertEquals(MoveTransactionStatus.COMMITTED, transaction.status());
        assertEquals(6, move.changes().size());
        assertArrayEquals(new int[] { 3, 4, 5, 0, 1, 2 }, fixture.state().assignments());
        assertEquals(0, verified.score().hardScore(), verified.score()::toString);
        assertEquals(
                fixture.full().calculateScore(fixture.problem(), fixture.state().snapshot()),
                fixture.state().score());
        assertRelationGroupSharesSlot(fixture.problem(), fixture.state().snapshot(), 0, "N");
    }

    @Test
    void relation_group_exchange의_reject_예외_cancellation은_상태와_cache를_완전복구한다() {
        assertRollback(exchangeFixture(3, false, false, false), RollbackMode.REJECT);
        assertRollback(exchangeFixture(3, false, false, false), RollbackMode.APPLY_EXCEPTION);
        assertRollback(exchangeFixture(3, false, false, false), RollbackMode.CANCELLATION);
    }

    @Test
    void pinned_source_target과_available_shift_code_불일치는_생성단계에서_차단된다() {
        ExchangeFixture pinnedSource = exchangeFixture(3, true, false, false);
        ExchangeFixture pinnedTarget = exchangeFixture(3, false, true, false);
        ExchangeFixture unavailableTarget = exchangeFixture(3, false, false, true);

        assertThrows(IllegalArgumentException.class, () -> exchangeMove(pinnedSource));
        assertThrows(IllegalArgumentException.class, () -> exchangeMove(pinnedTarget));
        assertThrows(IllegalArgumentException.class, () -> exchangeMove(unavailableTarget));
    }

    @Test
    void preceptor_10k는_feasible하고_relation_slot을_실제로_변경하며_전체평가와_일치한다()
            throws Exception {
        Fixture fixture = preceptorFixture();
        SolveResult<RosterSolution> result = solvePreceptor(fixture, 10_000L);

        assertPreceptorResult(fixture, result);
        assertEquals(
                result.score(),
                new FullScoreCalculator().calculateScore(fixture.problem(), result.bestSolution()),
                "동일 최종 assignment의 탐색/전체 평가 점수가 일치해야 합니다.");
        System.out.printf("PHASE5_PRECEPTOR_RELATION budget=10000 score=%s elapsedMs=%d evaluations=%d%n",
                result.score(), result.elapsedMillis(), result.evaluationCount());
    }

    @Test
    void preceptor_50k도_feasible하고_relation_slot을_실제로_변경한다() throws Exception {
        Fixture fixture = preceptorFixture();
        SolveResult<RosterSolution> result = solvePreceptor(fixture, 50_000L);

        assertPreceptorResult(fixture, result);
        System.out.printf("PHASE5_PRECEPTOR_RELATION budget=50000 score=%s elapsedMs=%d evaluations=%d%n",
                result.score(), result.elapsedMillis(), result.evaluationCount());
    }

    private static SolveResult<RosterSolution> solvePreceptor(Fixture fixture, long budget) {
        return new LahcSolverEngine().solve(
                fixture.problem(),
                SolveOptions.builder().maxEvaluations(budget).randomSeed(42L).build(),
                solution -> {
                });
    }

    private static void assertPreceptorResult(
            Fixture fixture, SolveResult<RosterSolution> result) {
        assertEquals(0, result.score().hardScore(), result.score()::toString);
        assertTrue(result.score().compareTo(PREVIOUS_PRECEPTOR_10K) >= 0,
                () -> "기존 Phase 5 기준보다 나빠졌습니다: " + result.score());
        assertNotEquals(
                relationSlots(fixture.problem(), fixture.initial()),
                relationSlots(fixture.problem(), result.bestSolution()),
                "relation group의 날짜·교대 패턴이 실제로 변경되어야 합니다.");
    }

    private static void assertRollback(ExchangeFixture fixture, RollbackMode mode) {
        int[] assignments = fixture.state().assignments();
        SolutionFingerprint fingerprint = fixture.state().fingerprint();
        RosterScore score = fixture.state().score();
        ScoreCacheFingerprint cache = fixture.incremental().cacheFingerprint();
        RelationGroupExchangeMove delegate = exchangeMove(fixture);
        Move move = mode == RollbackMode.APPLY_EXCEPTION ? failingAfterApply(delegate) : delegate;
        MoveTransaction transaction = MoveTransaction.open(fixture.state(), fixture.incremental());

        switch (mode) {
            case REJECT -> {
                transaction.apply(move);
                transaction.verifyCandidate();
                transaction.rollback();
            }
            case APPLY_EXCEPTION -> assertThrows(
                    IllegalStateException.class, () -> transaction.apply(move));
            case CANCELLATION -> {
                transaction.apply(move);
                assertThrows(MoveCancelledException.class,
                        () -> transaction.throwIfCancelled(() -> true));
            }
        }

        assertEquals(MoveTransactionStatus.ROLLED_BACK, transaction.status());
        assertArrayEquals(assignments, fixture.state().assignments());
        assertEquals(fingerprint, fixture.state().fingerprint());
        assertEquals(score, fixture.state().score());
        assertEquals(cache, fixture.incremental().cacheFingerprint());
        assertEquals(
                fixture.full().calculateScore(fixture.problem(), fixture.state().snapshot()),
                fixture.state().score());
    }

    private static Move failingAfterApply(Move delegate) {
        return new Move() {
            @Override
            public String moveType() {
                return delegate.moveType();
            }

            @Override
            public List<AssignmentChange> changes() {
                return delegate.changes();
            }

            @Override
            public void apply(SearchState state) {
                delegate.apply(state);
                throw new IllegalStateException("relation exchange apply failure injection");
            }

            @Override
            public void undo(SearchState state) {
                delegate.undo(state);
            }
        };
    }

    private static void assertCrossSlot(PlanningProblem problem, Move move) {
        Set<String> changedSlots = new HashSet<>();
        move.changes().forEach(change -> {
            PlanningProblem.ShiftData shift = problem.shifts().get(change.shiftIndex());
            changedSlots.add(shift.start().toLocalDate() + "\u0000" + shift.shiftCode());
        });
        assertEquals(2, changedSlots.size(),
                "relation move는 source와 target 실제일·교대를 함께 변경해야 합니다.");
    }

    private static void assertRelationGroupSharesSlot(
            PlanningProblem problem, RosterSolution solution, int groupId, String expectedCode) {
        PreceptorRelationIndex relations = new PreceptorRelationIndex(problem);
        Set<String> slots = new HashSet<>();
        for (int shiftIndex = 0; shiftIndex < problem.shiftCount(); shiftIndex++) {
            if (relations.groupId(solution.employeeIndex(shiftIndex)) == groupId) {
                PlanningProblem.ShiftData shift = problem.shifts().get(shiftIndex);
                slots.add(shift.start().toLocalDate() + "\u0000" + shift.shiftCode());
            }
        }
        assertEquals(Set.of(LocalDate.of(2026, 5, 21) + "\u0000" + expectedCode), slots);
    }

    private static String signature(Move move) {
        return move.moveType() + ":" + move.changes();
    }

    private static Fixture preceptorFixture() throws Exception {
        PlanningRequest request = OBJECT_MAPPER.readValue(
                JsonLoader.loadAsString("/json/preceptor.json"), PlanningRequest.class);
        PlanningProblem problem = new PlanningProblemMapper().toPlanningProblem(
                new EmployeeScheduleBuilder().build(request));
        FullScoreCalculator full = new FullScoreCalculator();
        RosterSolution initial = new InitialSolutionBuilder(full).build(problem).solution();
        return new Fixture(problem, initial, new SearchState(problem, initial));
    }

    private static ExchangeFixture exchangeFixture(
            int groupSize,
            boolean pinSource,
            boolean pinTarget,
            boolean relationCannotWorkTarget) {
        LocalDate date = LocalDate.of(2026, 5, 21);
        List<PlanningProblem.EmployeeData> employees = new ArrayList<>();
        for (int index = 0; index < groupSize; index++) {
            employees.add(employee(
                    "relation-" + index,
                    index == 0 ? null : "relation-0",
                    relationCannotWorkTarget ? Set.of("D") : Set.of("D", "N")));
        }
        for (int index = 0; index < groupSize; index++) {
            employees.add(employee("singleton-" + index, null, Set.of("D", "N")));
        }

        List<PlanningProblem.ShiftData> shifts = new ArrayList<>();
        for (int index = 0; index < groupSize; index++) {
            shifts.add(shift(index + 1L, "D", date.atTime(8, 0),
                    index, pinSource && index == 0));
        }
        for (int index = 0; index < groupSize; index++) {
            shifts.add(shift(groupSize + index + 1L, "N", date.atTime(20, 0),
                    groupSize + index, pinTarget && index == 0));
        }

        PlanningProblem problem = new PlanningProblem(
                new PlanningProblem.ScheduleWindow(
                        "tenant", "relation exchange", 0, 1, date, date.minusDays(1)),
                employees, shifts, List.of());
        int[] assignments = new int[groupSize * 2];
        Arrays.setAll(assignments, index -> index);
        FullScoreCalculator full = new FullScoreCalculator();
        RosterSolution unscored = new RosterSolution(
                problem.employeeCount(), assignments, RosterScore.of(0, 0, 0, 0, 0));
        RosterSolution initial = new RosterSolution(
                problem.employeeCount(), assignments, full.calculateScore(problem, unscored));
        SearchState state = new SearchState(problem, initial);
        return new ExchangeFixture(
                problem, initial, state, full,
                new IncrementalScoreCalculator(problem, initial, full), groupSize);
    }

    private static RelationGroupExchangeMove exchangeMove(ExchangeFixture fixture) {
        List<Integer> sourceShifts = new ArrayList<>();
        List<Integer> targetShifts = new ArrayList<>();
        for (int index = 0; index < fixture.groupSize(); index++) {
            sourceShifts.add(index);
            targetShifts.add(fixture.groupSize() + index);
        }
        return RelationGroupExchangeMove.create(
                fixture.problem(), fixture.state(), sourceShifts, targetShifts);
    }

    private static PlanningProblem.EmployeeData employee(
            String id, String preceptorId, Set<String> availableShiftCodes) {
        return new PlanningProblem.EmployeeData(
                id, id, Set.of("RN"), availableShiftCodes,
                0, 0, 0, 1, preceptorId);
    }

    private static PlanningProblem.ShiftData shift(
            long id,
            String code,
            LocalDateTime start,
            int employeeIndex,
            boolean pinned) {
        return new PlanningProblem.ShiftData(
                id, "shift-" + id, code, start, start.plusHours(8), start.toLocalDate(),
                "ward", "RN", pinned, employeeIndex,
                "N".equals(code) ? 1 : 0, 0);
    }

    private static Map<Integer, Map<String, Integer>> relationSlots(
            PlanningProblem problem, RosterSolution solution) {
        PreceptorRelationIndex relations = new PreceptorRelationIndex(problem);
        Map<Integer, Map<String, Integer>> result = new LinkedHashMap<>();
        for (int shiftIndex = 0; shiftIndex < problem.shiftCount(); shiftIndex++) {
            int employeeIndex = solution.employeeIndex(shiftIndex);
            int groupId = relations.groupId(employeeIndex);
            if (relations.employeesInGroup(groupId).size() < 2) {
                continue;
            }
            PlanningProblem.ShiftData shift = problem.shifts().get(shiftIndex);
            String slot = shift.start().toLocalDate() + "\u0000" + shift.shiftCode();
            result.computeIfAbsent(groupId, ignored -> new LinkedHashMap<>())
                    .merge(slot, 1, Integer::sum);
        }
        return result;
    }

    private enum RollbackMode {
        REJECT,
        APPLY_EXCEPTION,
        CANCELLATION
    }

    private record Fixture(
            PlanningProblem problem, RosterSolution initial, SearchState state) {
    }

    private record ExchangeFixture(
            PlanningProblem problem,
            RosterSolution initial,
            SearchState state,
            FullScoreCalculator full,
            IncrementalScoreCalculator incremental,
            int groupSize) {
    }
}
