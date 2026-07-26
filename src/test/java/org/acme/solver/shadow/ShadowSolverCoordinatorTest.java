package org.acme.solver.shadow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveListener;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.core.SolverEngine;
import org.acme.solver.core.TerminationReason;
import org.acme.solver.lahc.ExhaustivePrefixReassignIntensificationMetrics;
import org.acme.solver.lahc.PreceptorPrefixGuidedProtectedReassignSelector;
import org.acme.solver.score.FullScoreCalculator;
import org.junit.jupiter.api.Test;

class ShadowSolverCoordinatorTest {

    private final FullScoreCalculator full = new FullScoreCalculator();

    @Test
    void 네_모드는_명시된_primary만_반환하고_shadow는_listener에_노출하지_않는다() {
        PlanningProblem problem = problem(false);
        RosterSolution optaSolution = scored(problem, 0, 0);
        RosterSolution pojoSolution = scored(problem, 1, 0);
        SolveOptions options = SolveOptions.builder().maxEvaluations(10).randomSeed(77L).build();

        for (SolverMode mode : SolverMode.values()) {
            AtomicInteger optaCalls = new AtomicInteger();
            AtomicInteger pojoCalls = new AtomicInteger();
            AtomicInteger listenerCalls = new AtomicInteger();
            SolverEngine opta = stub(optaSolution, optaCalls);
            SolverEngine pojo = stub(pojoSolution, pojoCalls);

            SolveResult<RosterSolution> result = new ShadowSolverCoordinator(mode, opta, pojo)
                    .solve(problem, options, ignored -> listenerCalls.incrementAndGet());
            RosterSolution expected =
                    mode.primary() == SolverMode.EngineRole.OPTAPLANNER ? optaSolution : pojoSolution;

            assertArrayEquals(expected.employeeIndexByShift(),
                    result.bestSolution().employeeIndexByShift(), mode::name);
            assertEquals(expected.score(), result.score(), mode::name);
            assertEquals(1, listenerCalls.get(), "shadow listener는 외부 callback에 노출되면 안 됩니다.");
            assertEquals(mode.primary() == SolverMode.EngineRole.OPTAPLANNER || mode.hasShadow() ? 1 : 0,
                    optaCalls.get(), mode::name);
            assertEquals(mode.primary() == SolverMode.EngineRole.POJO || mode.hasShadow() ? 1 : 0,
                    pojoCalls.get(), mode::name);
            ShadowComparisonMetrics metrics = (ShadowComparisonMetrics) result.metrics();
            assertEquals(mode, metrics.mode());
            assertEquals(mode.primary(), metrics.primary().role());
            assertEquals(mode.shadow(), metrics.shadow() == null ? null : metrics.shadow().role());
        }
    }

    @Test
    void shadow_예외는_숨기지_않고_기록하지만_primary_assignment와_score를_바꾸지_않는다() {
        PlanningProblem problem = problem(false);
        RosterSolution primary = scored(problem, 0, 0);
        RuntimeException failure = new IllegalStateException("shadow-boom");
        SolverEngine throwingShadow = (ignoredProblem, ignoredOptions, ignoredListener) -> {
            throw failure;
        };

        SolveResult<RosterSolution> result = new ShadowSolverCoordinator(
                SolverMode.OPTAPLANNER_PRIMARY_SHADOW_POJO,
                stub(primary, new AtomicInteger()),
                throwingShadow).solve(
                        problem,
                        SolveOptions.builder().maxEvaluations(10).randomSeed(1L).build(),
                        SolveListener.noop());
        ShadowComparisonMetrics metrics = (ShadowComparisonMetrics) result.metrics();

        assertSame(primary, result.bestSolution());
        assertEquals(primary.score(), result.score());
        assertEquals(IllegalStateException.class.getName(), metrics.shadowFailureClass());
        assertEquals("shadow-boom", metrics.shadowFailureMessage());
        assertNull(metrics.shadow());
        assertEquals(ShadowComparisonMetrics.PojoOutcome.NOT_COMPARED, metrics.pojoOutcome());
    }

    @Test
    void primary_예외는_shadow로_fallback하지_않고_그대로_전파한다() {
        PlanningProblem problem = problem(false);
        AtomicInteger shadowCalls = new AtomicInteger();
        SolverEngine primary = (ignoredProblem, ignoredOptions, ignoredListener) -> {
            throw new IllegalArgumentException("primary-boom");
        };

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ShadowSolverCoordinator(
                        SolverMode.OPTAPLANNER_PRIMARY_SHADOW_POJO,
                        primary,
                        stub(scored(problem, 1, 0), shadowCalls)).solve(
                                problem,
                                SolveOptions.builder().maxEvaluations(10).build(),
                                SolveListener.noop()));

        assertEquals("primary-boom", failure.getMessage());
        assertEquals(0, shadowCalls.get());
    }

    @Test
    void POJO관점_WTL_최초차이_assignment차이와_rollback_operator를_구조화한다() {
        PlanningProblem problem = problem(false);
        RosterSolution optaSolution = scored(problem, 0, 0);
        RosterSolution pojoSolution = scored(problem, 1, 0);
        ExhaustivePrefixReassignIntensificationMetrics pojoMetrics =
                new ExhaustivePrefixReassignIntensificationMetrics(
                        2L,
                        2L,
                        0L,
                        2L,
                        3L,
                        0L,
                        0L,
                        5_400,
                        new PreceptorPrefixGuidedProtectedReassignSelector.Metrics(1L, 2L, 0L, 1L),
                        List.of(),
                        TerminationReason.MAX_EVALUATIONS_REACHED);
        SolverEngine pojo = (ignoredProblem, options, ignoredListener) -> new SolveResult<>(
                pojoSolution,
                pojoSolution.score(),
                TerminationReason.MAX_EVALUATIONS_REACHED,
                0L,
                2L,
                4L,
                options.randomSeed(),
                pojoMetrics);

        SolveResult<RosterSolution> result = new ShadowSolverCoordinator(
                SolverMode.OPTAPLANNER_PRIMARY_SHADOW_POJO,
                stub(optaSolution, new AtomicInteger()),
                pojo).solve(
                        problem,
                        SolveOptions.builder().maxEvaluations(10).randomSeed(7L).build(),
                        SolveListener.noop());
        ShadowComparisonMetrics metrics = (ShadowComparisonMetrics) result.metrics();

        assertEquals(ShadowComparisonMetrics.PojoOutcome.WIN, metrics.pojoOutcome());
        assertEquals("soft[1]", metrics.firstDifferenceObjective());
        assertEquals(1L, metrics.assignmentDifferenceCount());
        assertEquals(2L, metrics.shadow().rollbackAttemptCount());
        assertEquals(0L, metrics.shadow().rollbackFailureCount());
        assertEquals(1, metrics.shadow().operators().size());
        assertEquals("PrefixReassign",
                metrics.shadow().operators().getFirst().operatorId());
        assertEquals(2L, metrics.shadow().operators().getFirst().selectionCount());
        assertTrue(metrics.primary().fullScoreVerified());
        assertTrue(metrics.shadow().fullScoreVerified());
    }

    @Test
    void primary_final_score_mismatch와_pinned변경은_fatal_종료로_드러낸다() {
        PlanningProblem normal = problem(false);
        RosterSolution actual = scored(normal, 0, 0);
        SolveResult<RosterSolution> wrongScore = new SolveResult<>(
                actual,
                RosterScore.of(0, 999, 999, 999, 999),
                TerminationReason.COMPLETED,
                1L,
                1L,
                1L,
                1L);
        SolveResult<RosterSolution> mismatch = new ShadowSolverCoordinator(
                SolverMode.OPTAPLANNER_ONLY,
                (ignoredProblem, ignoredOptions, ignoredListener) -> wrongScore,
                stub(actual, new AtomicInteger())).solve(
                        normal,
                        SolveOptions.builder().maxEvaluations(1).build(),
                        SolveListener.noop());

        assertEquals(TerminationReason.SCORE_MISMATCH, mismatch.terminationReason());
        assertEquals(1L, ((ShadowComparisonMetrics) mismatch.metrics()).primary()
                .fullScoreMismatchCount());
        assertEquals(0L, ((ShadowComparisonMetrics) mismatch.metrics()).primary()
                .incrementalScoreMismatchCount());

        PlanningProblem pinned = problem(true);
        RosterSolution changedPinned = scored(pinned, 1, 0);
        SolveResult<RosterSolution> corruption = new ShadowSolverCoordinator(
                SolverMode.POJO_ONLY,
                stub(scored(pinned, 0, 0), new AtomicInteger()),
                stub(changedPinned, new AtomicInteger())).solve(
                        pinned,
                        SolveOptions.builder().maxEvaluations(1).build(),
                        SolveListener.noop());

        assertEquals(TerminationReason.STATE_CORRUPTION, corruption.terminationReason());
        assertEquals(1L, ((ShadowComparisonMetrics) corruption.metrics()).primary()
                .pinnedAssignmentChangeAttempts());
    }

    @Test
    void candidate와_invocation_fingerprint는_고정되고_seed나_mode가_바뀌면_호출값이_달라진다() {
        SolveOptions first = SolveOptions.builder().maxEvaluations(10).randomSeed(1L).build();
        SolveOptions repeated = SolveOptions.builder().maxEvaluations(10).randomSeed(1L).build();
        SolveOptions otherSeed = SolveOptions.builder().maxEvaluations(10).randomSeed(2L).build();

        assertEquals(64, SolverConfigFingerprint.candidateFingerprint().length());
        assertEquals(
                SolverConfigFingerprint.invocationFingerprint(SolverMode.OPTAPLANNER_ONLY, first),
                SolverConfigFingerprint.invocationFingerprint(SolverMode.OPTAPLANNER_ONLY, repeated));
        assertNotEquals(
                SolverConfigFingerprint.invocationFingerprint(SolverMode.OPTAPLANNER_ONLY, first),
                SolverConfigFingerprint.invocationFingerprint(SolverMode.OPTAPLANNER_ONLY, otherSeed));
        assertNotEquals(
                SolverConfigFingerprint.invocationFingerprint(SolverMode.OPTAPLANNER_ONLY, first),
                SolverConfigFingerprint.invocationFingerprint(SolverMode.POJO_ONLY, first));
        assertNotNull(SolverConfigFingerprint.CANDIDATE_DESCRIPTOR);
    }

    private SolverEngine stub(RosterSolution solution, AtomicInteger calls) {
        return (problem, options, listener) -> {
            calls.incrementAndGet();
            listener.onBestSolution(solution);
            return new SolveResult<>(
                    solution,
                    solution.score(),
                    TerminationReason.COMPLETED,
                    1L,
                    1L,
                    3L,
                    options.randomSeed());
        };
    }

    private RosterSolution scored(PlanningProblem problem, int... assignments) {
        RosterSolution provisional = new RosterSolution(
                problem.employeeCount(), assignments, RosterScore.of(0, 0, 0, 0, 0));
        return new RosterSolution(
                problem.employeeCount(), assignments, full.calculateScore(problem, provisional));
    }

    private static PlanningProblem problem(boolean pinned) {
        LocalDate first = LocalDate.of(2026, 7, 1);
        return new PlanningProblem(
                new PlanningProblem.ScheduleWindow(
                        "tenant", "shadow-fixture", 0, 31, first, first.minusDays(1)),
                List.of(employee("e0"), employee("e1")),
                List.of(
                        shift(1L, first, pinned),
                        shift(2L, first.plusDays(1), false)),
                List.of(new PlanningProblem.AvailabilityData(
                        null, 0, first, PlanningProblem.AvailabilityKind.UNDESIRED)));
    }

    private static PlanningProblem.EmployeeData employee(String id) {
        return new PlanningProblem.EmployeeData(
                id, id, Set.of("ALL"), Set.of("D"), 0, 0, 0, 1, null);
    }

    private static PlanningProblem.ShiftData shift(long id, LocalDate date, boolean pinned) {
        LocalDateTime start = date.atTime(8, 0);
        return new PlanningProblem.ShiftData(
                id,
                "s" + id,
                "D",
                start,
                start.plusHours(8),
                date,
                "ward",
                "ALL",
                pinned,
                pinned ? 0 : -1,
                0,
                0);
    }
}
