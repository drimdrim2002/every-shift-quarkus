package org.acme.solver.alns;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.move.SolutionFingerprint;
import org.acme.solver.score.ScoreCacheFingerprint;
import org.junit.jupiter.api.Test;

class AlnsIterationTransactionTest {

    private static final AlnsIterationConfig ONE_SHIFT_CONFIG =
            new AlnsIterationConfig(1.0d, 1, 1, 1, 2);

    @Test
    void 개선_candidate는_full_incremental_검증_후_commit한다() {
        AlnsTestSupport.Fixture fixture = AlnsTestSupport.skillFixture(false);
        DestroyOperator destroy = fixedDestroy("FIXED", 0);
        GreedyRepair repair = new GreedyRepair();
        AlnsIteration iteration = iteration(fixture, destroy.id(), repair.id());

        AlnsIterationResult result = iteration.execute(
                destroy, repair, new ImprovementOnlyAcceptance(),
                ONE_SHIFT_CONFIG, new Random(1L), () -> false);

        assertEquals(AlnsIterationStatus.ACCEPTED, result.status());
        assertEquals(0, fixture.state().employeeIndex(0));
        assertEquals(result.candidateScore(), fixture.state().score());
        assertEquals(
                fixture.full().calculateScore(fixture.problem(), fixture.state().snapshot()),
                fixture.state().score());
        assertEquals(fixture.state().snapshot(), fixture.incremental().lastVerifiedBest());
    }

    @Test
    void 악화_candidate는_reject하고_assignment_fingerprint_score_cache를_rollback한다() {
        AlnsTestSupport.Fixture fixture = AlnsTestSupport.skillFixture(true);
        DestroyOperator destroy = fixedDestroy("FIXED", 0);
        RepairOperator repair = assigningRepair("BAD_REPAIR", 1);
        AlnsIteration iteration = iteration(fixture, destroy.id(), repair.id());
        int[] assignments = fixture.state().assignments();
        SolutionFingerprint fingerprint = fixture.state().fingerprint();
        RosterScore score = fixture.state().score();
        ScoreCacheFingerprint cache = fixture.incremental().cacheFingerprint();

        AlnsIterationResult result = iteration.execute(
                destroy, repair, new ImprovementOnlyAcceptance(),
                ONE_SHIFT_CONFIG, new Random(1L), () -> false);

        assertEquals(AlnsIterationStatus.REJECTED, result.status());
        assertTrue(result.candidateScore().compareTo(score) < 0);
        assertArrayEquals(assignments, fixture.state().assignments());
        assertEquals(fingerprint, fixture.state().fingerprint());
        assertEquals(score, fixture.state().score());
        assertEquals(cache, fixture.incremental().cacheFingerprint());
        assertEquals(fixture.initial(), fixture.incremental().lastVerifiedBest());
    }

    @Test
    void repair_실패는_acceptance를_호출하지_않고_완전_rollback한다() {
        AlnsTestSupport.Fixture fixture = AlnsTestSupport.skillFixture(true);
        DestroyOperator destroy = fixedDestroy("FIXED", 0);
        RepairOperator repair = new RepairOperator() {
            @Override
            public String id() {
                return "FAIL_REPAIR";
            }

            @Override
            public RepairResult repair(RepairContext context) {
                context.beginAttempt();
                return RepairResult.failed(context.attempts());
            }
        };
        AtomicInteger acceptanceCalls = new AtomicInteger();
        AcceptancePolicy acceptance = countingAcceptance(acceptanceCalls);
        AlnsIteration iteration = iteration(fixture, destroy.id(), repair.id());
        StateCheckpoint checkpoint = checkpoint(fixture);

        AlnsIterationResult result = iteration.execute(
                destroy, repair, acceptance,
                ONE_SHIFT_CONFIG, new Random(1L), () -> false);

        assertEquals(AlnsIterationStatus.REPAIR_FAILED, result.status());
        assertEquals(1, result.repairAttempts());
        assertEquals(0, acceptanceCalls.get());
        assertEquals(null, result.candidateScore());
        assertRestored(fixture, checkpoint);
    }

    @Test
    void repair_예외와_취소도_partial을_노출하지_않고_완전_rollback한다() {
        AlnsTestSupport.Fixture exceptionFixture = AlnsTestSupport.skillFixture(true);
        DestroyOperator destroy = fixedDestroy("FIXED", 0);
        RepairOperator throwingRepair = new RepairOperator() {
            @Override
            public String id() {
                return "THROW_REPAIR";
            }

            @Override
            public RepairResult repair(RepairContext context) {
                context.beginAttempt();
                throw new IllegalStateException("주입된 repair 예외");
            }
        };
        AtomicInteger acceptanceCalls = new AtomicInteger();
        StateCheckpoint exceptionCheckpoint = checkpoint(exceptionFixture);
        AlnsIterationResult exceptionResult = iteration(
                exceptionFixture, destroy.id(), throwingRepair.id()).execute(
                        destroy, throwingRepair, countingAcceptance(acceptanceCalls),
                        ONE_SHIFT_CONFIG, new Random(1L), () -> false);

        assertEquals(AlnsIterationStatus.OPERATOR_EXCEPTION, exceptionResult.status());
        assertEquals(1, exceptionResult.repairAttempts());
        assertTrue(exceptionResult.diagnosticCode().startsWith("repair:"));
        assertEquals(0, acceptanceCalls.get());
        assertRestored(exceptionFixture, exceptionCheckpoint);

        AlnsTestSupport.Fixture cancelledFixture = AlnsTestSupport.skillFixture(true);
        AtomicInteger cancellationChecks = new AtomicInteger();
        StateCheckpoint cancelledCheckpoint = checkpoint(cancelledFixture);
        AlnsIterationResult cancelled = iteration(
                cancelledFixture, destroy.id(), GreedyRepair.ID).execute(
                        destroy, new GreedyRepair(), new ImprovementOnlyAcceptance(),
                        ONE_SHIFT_CONFIG, new Random(1L),
                        () -> cancellationChecks.incrementAndGet() >= 2);

        assertEquals(AlnsIterationStatus.CANCELLED, cancelled.status());
        assertRestored(cancelledFixture, cancelledCheckpoint);
    }

    @Test
    void partial_표현은_package_private이고_public_SPI에_score_solution_callback을_노출하지_않는다() {
        assertFalse(Modifier.isPublic(PartialSolution.class.getModifiers()));
        assertTrue(Arrays.stream(RepairContext.class.getMethods())
                .noneMatch(method -> method.getReturnType() == RosterScore.class
                        || method.getReturnType() == RosterSolution.class
                        || Arrays.asList(method.getParameterTypes()).contains(RosterScore.class)
                        || Arrays.asList(method.getParameterTypes()).contains(RosterSolution.class)));
        assertTrue(Arrays.stream(RepairContext.class.getMethods())
                .noneMatch(method -> method.getName().toLowerCase().contains("callback")
                        || method.getName().toLowerCase().contains("best")));
    }

    @Test
    void baseline_iteration_100회는_상태오염과_repair실패가_없다() {
        AlnsTestSupport.Fixture fixture = AlnsTestSupport.relationFixture();
        AlnsIteration iteration = new AlnsIteration(
                fixture.problem(), fixture.state(), fixture.incremental(),
                OperatorCompatibilityMatrix.baseline());
        DestroyOperator[] destroys = {
                new RandomRemoval(), new RelatedShiftRemoval(), new PreceptorRelationGroupRemoval()
        };
        RepairOperator[] repairs = {
                new GreedyRepair(), new Regret2Repair(), new RelationAwareRepair()
        };
        AlnsIterationConfig config = new AlnsIterationConfig(0.30d, 1, 3, 4, 2);
        int repairFailures = 0;

        for (int index = 0; index < 100; index++) {
            int operatorIndex = index % destroys.length;
            DestroyOperator destroy = destroys[operatorIndex];
            RepairOperator repair = destroy instanceof PreceptorRelationGroupRemoval
                    ? repairs[2]
                    : repairs[operatorIndex];
            AlnsIterationResult result = iteration.execute(
                    destroy, repair, new ImprovementOnlyAcceptance(),
                    config, new Random(20260715L + index), () -> false);
            if (result.status() == AlnsIterationStatus.REPAIR_FAILED
                    || result.status() == AlnsIterationStatus.OPERATOR_EXCEPTION) {
                repairFailures++;
            }
            assertNotNull(result.candidateScore(), "iteration=" + index + ", " + result);
            assertEquals(
                    fixture.full().calculateScore(fixture.problem(), fixture.state().snapshot()),
                    fixture.state().score(),
                    "iteration=" + index);
            assertFalse(fixture.state().isCorrupted());
        }

        System.out.printf("PHASE6A_BASELINE iterations=100 repairFailures=%d fingerprintScoreMismatch=0%n",
                repairFailures);
        assertEquals(0, repairFailures);
    }

    private static AlnsIteration iteration(
            AlnsTestSupport.Fixture fixture, String destroyId, String repairId) {
        return new AlnsIteration(
                fixture.problem(), fixture.state(), fixture.incremental(),
                new OperatorCompatibilityMatrix(Map.of(destroyId, Set.of(repairId))));
    }

    private static DestroyOperator fixedDestroy(String id, int shiftIndex) {
        return new DestroyOperator() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public DestroyPlan destroy(DestroyContext context) {
                return DestroyPlan.of(shiftIndex);
            }
        };
    }

    private static RepairOperator assigningRepair(String id, int employeeIndex) {
        return new RepairOperator() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public RepairResult repair(RepairContext context) {
                context.beginAttempt();
                context.assign(context.unassignedShiftIndexes()[0], employeeIndex);
                return RepairResult.completed(context.attempts());
            }
        };
    }

    private static AcceptancePolicy countingAcceptance(AtomicInteger calls) {
        return new AcceptancePolicy() {
            @Override
            public String id() {
                return "COUNTING";
            }

            @Override
            public boolean accept(RosterScore currentScore, RosterScore candidateScore) {
                calls.incrementAndGet();
                return false;
            }
        };
    }

    private static StateCheckpoint checkpoint(AlnsTestSupport.Fixture fixture) {
        return new StateCheckpoint(
                fixture.state().assignments(),
                fixture.state().fingerprint(),
                fixture.state().score(),
                fixture.incremental().cacheFingerprint(),
                fixture.incremental().lastVerifiedBest());
    }

    private static void assertRestored(
            AlnsTestSupport.Fixture fixture, StateCheckpoint checkpoint) {
        assertArrayEquals(checkpoint.assignments(), fixture.state().assignments());
        assertEquals(checkpoint.fingerprint(), fixture.state().fingerprint());
        assertEquals(checkpoint.score(), fixture.state().score());
        assertEquals(checkpoint.cacheFingerprint(), fixture.incremental().cacheFingerprint());
        assertEquals(checkpoint.verifiedBest(), fixture.incremental().lastVerifiedBest());
    }

    private record StateCheckpoint(
            int[] assignments,
            SolutionFingerprint fingerprint,
            RosterScore score,
            ScoreCacheFingerprint cacheFingerprint,
            RosterSolution verifiedBest) {
    }
}
