package org.acme.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.EmployeeScheduleBuilder;
import org.acme.model.EmployeeSchedule;
import org.acme.solver.adapter.EmployeeScheduleProjection;
import org.acme.solver.adapter.PlanningProblemMapper;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveListener;
import org.acme.solver.core.SolveMetrics;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.core.SolverEngine;
import org.acme.solver.core.TerminationReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Fake engine 으로 multi-pass 루프·콜백·warmStart·종료 정책을 검증한다.
 */
class SolverRunnerIncrementalMultiPassTest {

    private SolverRunner runner;
    private SequenceSolverEngine engine;
    private AtomicInteger passEndCount;
    private AtomicInteger innerBestCount;

    @BeforeEach
    void setUp() {
        runner = new SolverRunner();
        runner.incrementalEnabled = true;
        runner.firstIterationSeconds = 1;
        runner.iterationSeconds = 1;
        runner.maxTotalMinutes = 10;
        runner.minIterations = 1;
        runner.maxIterations = 6;
        runner.saveOnPassEnd = true;
        runner.saveOnInnerBest = false;
        runner.seedMode = SolverRunner.SEED_MODE_BASE_PLUS_PASS;
        runner.randomSeed = 42L;

        engine = new SequenceSolverEngine();
        runner.solverEngine = engine;

        PlanningProblem problem = minimalProblem();
        runner.employeeScheduleBuilder = new EmployeeScheduleBuilder() {
            @Override
            public EmployeeSchedule build(PlanningRequest request) {
                return new EmployeeSchedule();
            }
        };
        runner.planningProblemMapper = new PlanningProblemMapper() {
            @Override
            public PlanningProblem toPlanningProblem(EmployeeSchedule schedule) {
                return problem;
            }
        };
        runner.employeeScheduleProjection = new EmployeeScheduleProjection() {
            @Override
            public EmployeeSchedule toEmployeeSchedule(PlanningProblem p, RosterSolution solution) {
                EmployeeSchedule schedule = new EmployeeSchedule();
                if (solution != null) {
                    schedule.setScore(solution.score());
                }
                return schedule;
            }
        };

        passEndCount = new AtomicInteger();
        innerBestCount = new AtomicInteger();
    }

    @Test
    void pass1_hardFeasible_stopsAfterOnePassAndOnePassEndCallback() {
        engine.enqueue(score(0, -100));

        EmployeeSchedule result = runner.solveIncremental(
                null,
                "exec-1",
                s -> passEndCount.incrementAndGet(),
                s -> innerBestCount.incrementAndGet());

        assertEquals(1, engine.solveCalls);
        assertEquals(1, passEndCount.get());
        assertEquals(0, innerBestCount.get());
        assertEquals(score(0, -100), result.getScore());
        assertTrue(engine.warmStarts.get(0) == null);
    }

    @Test
    void hardViolated_retriesUntilFeasibleThenStopsWhenNoImprovement() {
        // pass1 hard<0 continue, pass2 hard=0 improved continue, pass3 same hard=0 stop
        engine.enqueue(score(-2, -50));
        engine.enqueue(score(0, -40));
        engine.enqueue(score(0, -40));

        EmployeeSchedule result = runner.solveIncremental(
                null,
                "exec-2",
                s -> passEndCount.incrementAndGet());

        assertEquals(3, engine.solveCalls);
        assertEquals(3, passEndCount.get());
        assertEquals(score(0, -40), result.getScore());
        // pass2,3 warmStart 는 이전 best
        assertNotNull(engine.warmStarts.get(1));
        assertEquals(score(-2, -50), engine.warmStarts.get(1).score());
        assertEquals(score(0, -40), engine.warmStarts.get(2).score());
    }

    @Test
    void hardNeverFeasible_stopsAtMaxIterations() {
        for (int i = 0; i < 6; i++) {
            engine.enqueue(score(-1, -i));
        }

        EmployeeSchedule result = runner.solveIncremental(
                null,
                "exec-3",
                s -> passEndCount.incrementAndGet());

        assertEquals(6, engine.solveCalls);
        assertEquals(6, passEndCount.get());
        // 엄격 개선 시퀀스: -1/-0, -1/-1 은 soft 개선이 아니라 hard 동일 soft 악화 → best 유지
        // score(-1, -i): soft[0]=-i 가 점점 나빠지므로 best 는 첫 패스 score(-1, 0)
        assertEquals(score(-1, 0), result.getScore());
    }

    @Test
    void hardNeverFeasible_improvingScores_keepsBestAndRunsSixPasses() {
        for (int i = 0; i < 6; i++) {
            // soft 개선 (덜 나쁨): -100, -90, ...
            engine.enqueue(score(-1, -100 + i * 10));
        }

        EmployeeSchedule result = runner.solveIncremental(
                null,
                "exec-4",
                s -> passEndCount.incrementAndGet());

        assertEquals(6, engine.solveCalls);
        assertEquals(6, passEndCount.get());
        // i=0..5 → soft0 = -100 + 10*i → 최종 best soft0=-50
        assertEquals(score(-1, -50), result.getScore());
    }

    @Test
    void passEndCallback_notInnerBest_evenWhenEngineReportsMultipleBests() {
        engine.innerBestEmitsPerSolve = 3;
        engine.enqueue(score(0, -10));

        runner.solveIncremental(
                null,
                "exec-5",
                s -> passEndCount.incrementAndGet(),
                s -> innerBestCount.incrementAndGet());

        assertEquals(1, passEndCount.get());
        assertEquals(0, innerBestCount.get());
    }

    @Test
    void saveOnInnerBest_true_invokesInnerCallback() {
        runner.saveOnInnerBest = true;
        engine.innerBestEmitsPerSolve = 2;
        engine.enqueue(score(0, -5));

        runner.solveIncremental(
                null,
                "exec-6",
                s -> passEndCount.incrementAndGet(),
                s -> innerBestCount.incrementAndGet());

        assertEquals(1, passEndCount.get());
        assertEquals(2, innerBestCount.get());
    }

    @Test
    void seedMode_basePlusPass_offsetsSeed() {
        engine.enqueue(score(0, 0));

        runner.solveIncremental(null, "exec-7", s -> {
        });

        assertEquals(1, engine.seeds.size());
        assertEquals(42L + 1L, engine.seeds.get(0));
    }

    @Test
    void seedMode_base_usesConstantSeed() {
        runner.seedMode = SolverRunner.SEED_MODE_BASE;
        engine.enqueue(score(-1, 0));
        engine.enqueue(score(0, 0)); // hard 복구 + 개선 → CONTINUE
        engine.enqueue(score(0, 0)); // 비개선 → STOP

        runner.solveIncremental(null, "exec-8", s -> {
        });

        assertEquals(3, engine.seeds.size());
        assertEquals(42L, engine.seeds.get(0));
        assertEquals(42L, engine.seeds.get(1));
        assertEquals(42L, engine.seeds.get(2));
    }

    @Test
    void enabledFalse_usesOneShotSolve() {
        runner.incrementalEnabled = false;
        runner.spentLimit = 5;
        engine.enqueue(score(0, -1));

        EmployeeSchedule result = runner.solveIncremental(null, "exec-9", s -> passEndCount.incrementAndGet());

        assertEquals(1, engine.solveCalls);
        assertEquals(0, passEndCount.get()); // 1-shot 경로, pass-end 없음
        assertEquals(score(0, -1), result.getScore());
    }

    @Test
    void nonImprovingWorsePass_doesNotReplaceGlobalBest() {
        engine.enqueue(score(0, -10)); // pass1 hard>=0 → stop after 1
        // only one pass because hard feasible

        EmployeeSchedule result = runner.solveIncremental(null, "exec-10", s -> passEndCount.incrementAndGet());
        assertEquals(1, engine.solveCalls);
        assertEquals(score(0, -10), result.getScore());
    }

    @Test
    void continueOnImprovement_thenConverge() {
        // pass1 hard 위반
        engine.enqueue(score(-1, -50));
        // pass2 hard 0 개선 → continue
        engine.enqueue(score(0, -30));
        // pass3 추가 개선 → continue
        engine.enqueue(score(0, -20));
        // pass4 비개선 → stop
        engine.enqueue(score(0, -25));

        EmployeeSchedule result = runner.solveIncremental(null, "exec-11", s -> passEndCount.incrementAndGet());

        assertEquals(4, engine.solveCalls);
        assertEquals(4, passEndCount.get());
        assertEquals(score(0, -20), result.getScore());
        // 마지막 warmStart 는 전역 best
        assertEquals(score(0, -20), engine.warmStarts.get(3).score());
    }

    private static RosterScore score(int hard, int soft0) {
        return RosterScore.of(hard, soft0, 0, 0, 0);
    }

    private static PlanningProblem minimalProblem() {
        LocalDate date = LocalDate.of(2026, 4, 1);
        LocalDateTime start = date.atTime(8, 0);
        return new PlanningProblem(
                new PlanningProblem.ScheduleWindow(
                        "tenant", "multi-pass-test", 0, 7, date, date.minusDays(1)),
                List.of(new PlanningProblem.EmployeeData(
                        "e0", "e0", Set.of("ALL"), Set.of("D"), 0, 0, 0, 1, null)),
                List.of(new PlanningProblem.ShiftData(
                        1L, "s1", "D", start, start.plusHours(8), date, "ward", "ALL", false, 0, 0, 0)),
                List.of());
    }

    /**
     * 패스마다 정해진 score 시퀀스를 반환하는 Fake engine.
     */
    private static final class SequenceSolverEngine implements SolverEngine {
        private final List<RosterScore> scores = new ArrayList<>();
        private int nextIndex;
        int solveCalls;
        int innerBestEmitsPerSolve;
        final List<RosterSolution> warmStarts = new ArrayList<>();
        final List<Long> seeds = new ArrayList<>();

        void enqueue(RosterScore score) {
            scores.add(score);
        }

        @Override
        public SolveResult<RosterSolution> solve(
                PlanningProblem problem,
                SolveOptions options,
                SolveListener listener) {
            solveCalls++;
            warmStarts.add(options.warmStart().orElse(null));
            seeds.add(options.randomSeed());

            if (nextIndex >= scores.size()) {
                throw new IllegalStateException("Fake engine score 시퀀스 고갈: call=" + solveCalls);
            }
            RosterScore score = scores.get(nextIndex++);
            RosterSolution solution = new RosterSolution(problem.employeeCount(), new int[] {0}, score);

            for (int i = 0; i < innerBestEmitsPerSolve; i++) {
                listener.onBestSolution(solution);
            }

            return new SolveResult<>(
                    solution,
                    score,
                    TerminationReason.COMPLETED,
                    1L,
                    1L,
                    1L,
                    options.randomSeed(),
                    SolveMetrics.empty());
        }
    }
}
