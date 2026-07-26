package org.acme.solver.move;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.TerminationReason;
import org.acme.solver.score.ConstraintDependencyMetadata;
import org.acme.solver.score.ConstraintEvaluator;
import org.acme.solver.score.ContributionCollector;
import org.acme.solver.score.DependencyDimension;
import org.acme.solver.score.FairnessConstraint;
import org.acme.solver.score.FullScoreCalculator;
import org.acme.solver.score.IncrementalScoreCalculator;
import org.acme.solver.score.ScoreCacheFingerprint;
import org.acme.solver.score.ScoreEvaluationContext;
import org.acme.solver.score.ScoreLevel;
import org.acme.solver.score.ScoreMismatchException;
import org.junit.jupiter.api.Test;

class MoveTransactionTest {

    @Test
    void NEW_ACTIVE_COMMITTED_상태전이와_여러_move의_원자적_commit() {
        Phase4TestSupport.Fixture fixture = Phase4TestSupport.fixture("request.json");
        int first = Phase4TestSupport.mutableShift(fixture.problem());
        int second = Phase4TestSupport.mutableShift(fixture.problem(), first);
        ReassignMove firstMove = reassign(fixture, first, 0);
        ReassignMove secondMove = reassign(fixture, second, 1);
        int[] baseline = fixture.state().assignments();

        MoveTransaction transaction = new MoveTransaction(fixture.state(), fixture.incremental());
        assertEquals(MoveTransactionStatus.NEW, transaction.status());
        transaction.begin();
        assertEquals(MoveTransactionStatus.ACTIVE, transaction.status());
        transaction.apply(firstMove);
        transaction.apply(secondMove);
        RosterSolution verified = transaction.verifyCandidate();
        transaction.commit();

        assertEquals(MoveTransactionStatus.COMMITTED, transaction.status());
        assertEquals(2, transaction.appliedMoveCount());
        assertNotEquals(java.util.Arrays.toString(baseline), java.util.Arrays.toString(fixture.state().assignments()));
        assertEquals(verified.score(), fixture.state().score());
        assertEquals(
                fixture.full().calculateScore(fixture.problem(), fixture.state().snapshot()),
                fixture.state().score());
    }

    @Test
    void reject는_여러_move를_역순_rollback한다() {
        Phase4TestSupport.Fixture fixture = Phase4TestSupport.fixture("request.json");
        int first = Phase4TestSupport.mutableShift(fixture.problem());
        int second = Phase4TestSupport.mutableShift(fixture.problem(), first);
        int[] baseline = fixture.state().assignments();
        SolutionFingerprint fingerprint = fixture.state().fingerprint();
        RosterScore score = fixture.state().score();
        ScoreCacheFingerprint cache = fixture.incremental().cacheFingerprint();

        MoveTransaction transaction = MoveTransaction.open(fixture.state(), fixture.incremental());
        transaction.apply(reassign(fixture, first, 0));
        transaction.apply(reassign(fixture, second, 1));
        transaction.rollback();

        assertEquals(MoveTransactionStatus.ROLLED_BACK, transaction.status());
        assertArrayEquals(baseline, fixture.state().assignments());
        assertEquals(fingerprint, fixture.state().fingerprint());
        assertEquals(score, fixture.state().score());
        assertEquals(cache, fixture.incremental().cacheFingerprint());
    }

    @Test
    void stale_move_예외는_이미_적용한_move까지_rollback한다() {
        Phase4TestSupport.Fixture fixture = Phase4TestSupport.fixture("request.json");
        int shift = Phase4TestSupport.mutableShift(fixture.problem());
        int[] baseline = fixture.state().assignments();
        ReassignMove first = reassign(fixture, shift, 0);
        ReassignMove stale = reassign(fixture, shift, 3);

        MoveTransaction transaction = MoveTransaction.open(fixture.state(), fixture.incremental());
        transaction.apply(first);
        assertThrows(IllegalStateException.class, () -> transaction.apply(stale));

        assertEquals(MoveTransactionStatus.ROLLED_BACK, transaction.status());
        assertArrayEquals(baseline, fixture.state().assignments());
        assertEquals(fixture.initial().score(), fixture.state().score());
    }

    @Test
    void cancellation_주입은_역순_rollback_후_CANCELLED를_전달한다() {
        Phase4TestSupport.Fixture fixture = Phase4TestSupport.fixture("request.json");
        int shift = Phase4TestSupport.mutableShift(fixture.problem());
        int[] baseline = fixture.state().assignments();

        MoveTransaction transaction = MoveTransaction.open(fixture.state(), fixture.incremental());
        transaction.apply(reassign(fixture, shift, 0));
        MoveCancelledException exception = assertThrows(
                MoveCancelledException.class,
                () -> transaction.throwIfCancelled(() -> true));

        assertEquals(TerminationReason.CANCELLED, exception.terminationReason());
        assertEquals(MoveTransactionStatus.ROLLED_BACK, transaction.status());
        assertArrayEquals(baseline, fixture.state().assignments());
    }

    @Test
    void full_incremental_mismatch는_SCORE_MISMATCH와_마지막_verified_best만_남긴다() {
        Phase4TestSupport.Fixture base = Phase4TestSupport.fixture("request.json");
        AtomicBoolean mismatchEnabled = new AtomicBoolean(false);
        ConstraintEvaluator evaluator = new FullOnlyMismatchEvaluator(mismatchEnabled);
        FullScoreCalculator full = new FullScoreCalculator(List.of(evaluator));
        RosterSolution zeroScored = new RosterSolution(
                base.problem().employeeCount(),
                base.initial().employeeIndexByShift(),
                RosterScore.of(0, 0, 0, 0, 0));
        SearchState state = new SearchState(base.problem(), zeroScored);
        IncrementalScoreCalculator incremental = new IncrementalScoreCalculator(
                base.problem(), zeroScored, full);
        int shift = Phase4TestSupport.mutableShift(base.problem());
        ReassignMove move = ReassignMove.create(
                base.problem(), state, shift,
                Phase4TestSupport.differentEmployee(
                        base.problem(), state.employeeIndex(shift), 0));
        int[] baseline = state.assignments();

        MoveTransaction transaction = MoveTransaction.open(state, incremental);
        transaction.apply(move);
        mismatchEnabled.set(true);
        ScoreMismatchException mismatch = assertThrows(
                ScoreMismatchException.class, transaction::verifyCandidate);

        assertEquals(TerminationReason.SCORE_MISMATCH, mismatch.terminationReason());
        assertEquals(zeroScored, mismatch.lastVerifiedBest());
        assertEquals(MoveTransactionStatus.ROLLED_BACK, transaction.status());
        assertArrayEquals(baseline, state.assignments());
        assertEquals(zeroScored.score(), state.score());
    }

    @Test
    void fingerprint_복구실패는_STATE_CORRUPTION으로_즉시_격리한다() {
        Phase4TestSupport.Fixture fixture = Phase4TestSupport.fixture("request.json");
        int shift = Phase4TestSupport.mutableShift(fixture.problem());
        int oldEmployee = fixture.state().employeeIndex(shift);
        AssignmentChange change = new AssignmentChange(
                shift, oldEmployee,
                Phase4TestSupport.differentEmployee(fixture.problem(), oldEmployee, 0));
        Move badUndo = new Move() {
            @Override
            public String moveType() {
                return "BAD_UNDO_TEST";
            }

            @Override
            public List<AssignmentChange> changes() {
                return List.of(change);
            }

            @Override
            public void apply(SearchState state) {
                state.changeAssignment(
                        change.shiftIndex(), change.oldEmployeeIndex(), change.newEmployeeIndex());
            }

            @Override
            public void undo(SearchState state) {
                // 실패 주입: 의도적으로 역연산을 수행하지 않습니다.
            }
        };

        MoveTransaction transaction = MoveTransaction.open(fixture.state(), fixture.incremental());
        transaction.apply(badUndo);
        StateCorruptionException corruption = assertThrows(
                StateCorruptionException.class, transaction::rollback);

        assertEquals(TerminationReason.STATE_CORRUPTION, corruption.terminationReason());
        assertEquals(fixture.initial(), corruption.lastVerifiedBest());
        assertEquals(MoveTransactionStatus.CORRUPTED, transaction.status());
        assertTrue(fixture.state().isCorrupted());
        assertThrows(IllegalStateException.class, fixture.state()::snapshot);
    }

    @Test
    void score_overflow는_assignment_score_cache를_오염시키지_않고_rollback한다() {
        PlanningProblem problem = overflowProblem();
        FullScoreCalculator full = new FullScoreCalculator(List.of(new FairnessConstraint()));
        RosterSolution unscored = new RosterSolution(
                problem.employeeCount(), new int[] { 0, 1 }, RosterScore.of(0, 0, 0, 0, 0));
        RosterSolution initial = new RosterSolution(
                problem.employeeCount(), unscored.employeeIndexByShift(), full.calculateScore(problem, unscored));
        SearchState state = new SearchState(problem, initial);
        IncrementalScoreCalculator incremental = new IncrementalScoreCalculator(problem, initial, full);
        ScoreCacheFingerprint cache = incremental.cacheFingerprint();
        SolutionFingerprint fingerprint = state.fingerprint();

        MoveTransaction transaction = MoveTransaction.open(state, incremental);
        ReassignMove overflowing = ReassignMove.create(problem, state, 1, 0);
        assertThrows(ArithmeticException.class, () -> transaction.apply(overflowing));

        assertEquals(MoveTransactionStatus.ROLLED_BACK, transaction.status());
        assertArrayEquals(new int[] { 0, 1 }, state.assignments());
        assertEquals(initial.score(), state.score());
        assertEquals(fingerprint, state.fingerprint());
        assertEquals(cache, incremental.cacheFingerprint());
    }

    private static ReassignMove reassign(
            Phase4TestSupport.Fixture fixture, int shiftIndex, int salt) {
        int oldEmployee = fixture.state().employeeIndex(shiftIndex);
        return ReassignMove.create(
                fixture.problem(), fixture.state(), shiftIndex,
                Phase4TestSupport.differentEmployee(fixture.problem(), oldEmployee, salt));
    }

    private static PlanningProblem overflowProblem() {
        PlanningProblem.ScheduleWindow window = new PlanningProblem.ScheduleWindow(
                "tenant", "overflow", 0, 2,
                LocalDate.of(2026, 1, 1), LocalDate.of(2025, 12, 31));
        List<PlanningProblem.EmployeeData> employees = List.of(
                employee("e0"), employee("e1"));
        List<PlanningProblem.ShiftData> shifts = List.of(
                shift(1L, LocalDateTime.of(2026, 1, 1, 20, 0), 0),
                shift(2L, LocalDateTime.of(2026, 1, 2, 20, 0), 1));
        return new PlanningProblem(window, employees, shifts, List.of());
    }

    private static PlanningProblem.EmployeeData employee(String id) {
        return new PlanningProblem.EmployeeData(
                id, id, Set.of("RN"), Set.of("N"), 0, 0, 0, 1, null);
    }

    private static PlanningProblem.ShiftData shift(
            long id, LocalDateTime start, int initialEmployee) {
        return new PlanningProblem.ShiftData(
                id, "s" + id, "N", start, start.plusHours(8), start.toLocalDate(),
                "ward", "RN", false, initialEmployee, 30_000, 0);
    }

    private static final class FullOnlyMismatchEvaluator implements ConstraintEvaluator {

        private final AtomicBoolean mismatchEnabled;

        private FullOnlyMismatchEvaluator(AtomicBoolean mismatchEnabled) {
            this.mismatchEnabled = mismatchEnabled;
        }

        @Override
        public String evaluatorId() {
            return "full-only-mismatch-test";
        }

        @Override
        public ConstraintDependencyMetadata dependencyMetadata() {
            return ConstraintDependencyMetadata.employee(
                    evaluatorId(), Duration.ZERO, Duration.ZERO, 0, 0,
                    DependencyDimension.FAIRNESS_AGGREGATE);
        }

        @Override
        public void evaluate(ScoreEvaluationContext context, ContributionCollector collector) {
            if (mismatchEnabled.get()
                    && context.employeeIndexes().length == context.problem().employeeCount()) {
                collector.penalize(
                        "full-only-mismatch-test", ScoreLevel.HARD, 1L, List.of(), List.of());
            }
        }
    }
}
