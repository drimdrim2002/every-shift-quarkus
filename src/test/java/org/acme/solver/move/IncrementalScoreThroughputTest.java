package org.acme.solver.move;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.acme.solver.core.RosterSolution;
import org.junit.jupiter.api.Test;

class IncrementalScoreThroughputTest {

    private static final int WARMUP_PAIRS = 300;
    private static final int MEASURE_PAIRS = 600;
    private static final int ROUNDS = 7;

    @Test
    void 운영입력에서_증분평가_throughput이_전체평가보다_유의미하게_높다() {
        Phase4TestSupport.Fixture fixture = Phase4TestSupport.fixture("request.json");
        int shift = Phase4TestSupport.mutableShift(fixture.problem());
        int oldEmployee = fixture.initial().employeeIndex(shift);
        int newEmployee = Phase4TestSupport.differentEmployee(fixture.problem(), oldEmployee, 0);
        ReassignMove move = ReassignMove.create(
                fixture.problem(), fixture.state(), shift, newEmployee);
        MoveImpact impact = new MoveImpactResolver(
                fixture.problem(), fixture.incremental().dependencyMetadata()).resolve(move);

        int[] candidateAssignments = fixture.initial().employeeIndexByShift();
        candidateAssignments[shift] = newEmployee;
        RosterSolution candidate = new RosterSolution(
                fixture.problem().employeeCount(), candidateAssignments, fixture.initial().score());

        for (int index = 0; index < WARMUP_PAIRS; index++) {
            fixture.incremental().refreshAfterMove(candidate, impact);
            fixture.incremental().refreshAfterMove(fixture.initial(), impact);
            fixture.full().calculateScore(fixture.problem(), candidate);
            fixture.full().calculateScore(fixture.problem(), fixture.initial());
        }

        long[] incrementalNanos = new long[ROUNDS];
        long[] fullNanos = new long[ROUNDS];
        long checksum = 0L;
        for (int round = 0; round < ROUNDS; round++) {
            if ((round & 1) == 0) {
                incrementalNanos[round] = measureIncremental(fixture, candidate, impact);
                fullNanos[round] = measureFull(fixture, candidate);
            } else {
                fullNanos[round] = measureFull(fixture, candidate);
                incrementalNanos[round] = measureIncremental(fixture, candidate, impact);
            }
            checksum += fixture.incremental().currentScore().hashCode();
        }

        double incrementalPerSecond = evaluationsPerSecond(median(incrementalNanos));
        double fullPerSecond = evaluationsPerSecond(median(fullNanos));
        double speedup = incrementalPerSecond / fullPerSecond;
        System.out.printf(
                "PHASE4_THROUGHPUT dataset=request.json full=%.0f eval/s incremental=%.0f eval/s speedup=%.2fx checksum=%d%n",
                fullPerSecond, incrementalPerSecond, speedup, checksum);

        assertTrue(speedup >= 1.20,
                () -> "증분 평가 개선이 유의미하지 않습니다: speedup=" + speedup);
    }

    private static long measureIncremental(
            Phase4TestSupport.Fixture fixture,
            RosterSolution candidate,
            MoveImpact impact) {
        long start = System.nanoTime();
        for (int index = 0; index < MEASURE_PAIRS; index++) {
            fixture.incremental().refreshAfterMove(candidate, impact);
            fixture.incremental().refreshAfterMove(fixture.initial(), impact);
        }
        return System.nanoTime() - start;
    }

    private static long measureFull(
            Phase4TestSupport.Fixture fixture, RosterSolution candidate) {
        long start = System.nanoTime();
        for (int index = 0; index < MEASURE_PAIRS; index++) {
            fixture.full().calculateScore(fixture.problem(), candidate);
            fixture.full().calculateScore(fixture.problem(), fixture.initial());
        }
        return System.nanoTime() - start;
    }

    private static long median(long[] values) {
        long[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static double evaluationsPerSecond(long nanosForRound) {
        return (MEASURE_PAIRS * 2.0) / (nanosForRound / 1_000_000_000.0);
    }
}
