package org.acme.solver.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.EmployeeScheduleBuilder;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.acme.solver.adapter.EmployeeScheduleProjection;
import org.acme.solver.adapter.PlanningProblemMapper;
import org.acme.solver.algorithm.EmployeeSchedulingConstraintProvider;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.lahc.OptaStyleLocalSearchEngine;
import org.acme.solver.optaplanner.OptaPlannerScoreAdapter;
import org.acme.solver.score.FullScoreCalculator;
import org.acme.solver.score.ConstraintIds;
import org.acme.solver.score.ScoreCalculationResult;
import org.acme.test.JsonLoader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.score.buildin.bendable.BendableScore;
import org.optaplanner.core.api.solver.SolutionManager;
import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.config.solver.EnvironmentMode;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;

import com.fasterxml.jackson.databind.ObjectMapper;

/** fixed holdout의 fairness -2 패배를 같은 최종해 점수와 breakdown으로 진단합니다. */
@Tag("benchmark")
class FairnessHoldoutDiagnosticTest {

    private static final long SEED = 101L;
    private static final long OPTAPLANNER_EVALUATIONS = 2_296_836L;
    private static final long POJO_EVALUATIONS = 381_210L;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final EmployeeScheduleBuilder scheduleBuilder = new EmployeeScheduleBuilder();
    private final PlanningProblemMapper problemMapper = new PlanningProblemMapper();
    private final EmployeeScheduleProjection projection = new EmployeeScheduleProjection();
    private final FullScoreCalculator fullScoreCalculator = new FullScoreCalculator();
    private final SolutionManager<EmployeeSchedule, BendableScore> solutionManager = SolutionManager.create(
            SolverFactory.<EmployeeSchedule>create(new SolverConfig()
                    .withSolutionClass(EmployeeSchedule.class)
                    .withEntityClasses(Shift.class)
                    .withConstraintProviderClass(EmployeeSchedulingConstraintProvider.class)));

    @Test
    void fixed_holdout_seed101의_fairness_2점_차이는_점수기불일치가_아니다() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("fairness.holdout.diagnostic.enabled"));
        PlanningRequest request = objectMapper.readValue(
                JsonLoader.loadAsString("/json/fairness.json"), PlanningRequest.class);
        EmployeeSchedule source = scheduleBuilder.build(request);
        PlanningProblem problem = problemMapper.toPlanningProblem(source);

        EmployeeSchedule optaSchedule = optaSolver().solve(scheduleBuilder.build(request));
        RosterSolution optaSolution = projection.toRosterSolution(problem, optaSchedule);
        SolveResult<RosterSolution> pojoResult = new OptaStyleLocalSearchEngine().solve(
                problem,
                SolveOptions.builder()
                        .maxEvaluations(POJO_EVALUATIONS)
                        .randomSeed(SEED)
                        .build(),
                ignored -> {
                });
        RosterSolution pojoSolution = pojoResult.bestSolution();

        ScoreCalculationResult optaPojoBreakdown =
                fullScoreCalculator.calculateWithBreakdown(problem, optaSolution);
        ScoreCalculationResult pojoBreakdown =
                fullScoreCalculator.calculateWithBreakdown(problem, pojoSolution);
        RosterScore optaRecalculated = OptaPlannerScoreAdapter.toRosterScore(
                solutionManager.update(projection.toEmployeeSchedule(problem, optaSolution)));
        RosterScore pojoRecalculated = OptaPlannerScoreAdapter.toRosterScore(
                solutionManager.update(projection.toEmployeeSchedule(problem, pojoSolution)));

        assertEquals(optaSolution.score(), optaPojoBreakdown.score());
        assertEquals(optaSolution.score(), optaRecalculated);
        assertEquals(pojoSolution.score(), pojoBreakdown.score());
        assertEquals(pojoSolution.score(), pojoRecalculated);
        assertEquals(RosterScore.of(0, 0, 0, -7_626, 0), optaSolution.score());
        assertEquals(RosterScore.of(0, 0, 0, -7_628, 0), pojoSolution.score());

        System.out.printf(
                "FAIRNESS_HOLDOUT_DIAGNOSTIC opta=%s pojo=%s changedShifts=%d%n",
                optaSolution.score(), pojoSolution.score(), changedShiftCount(optaSolution, pojoSolution));
        printFairness("OPTA", problem, optaPojoBreakdown);
        printFairness("POJO", problem, pojoBreakdown);
    }

    private static Solver<EmployeeSchedule> optaSolver() {
        return SolverFactory.<EmployeeSchedule>create(new SolverConfig()
                .withSolutionClass(EmployeeSchedule.class)
                .withEntityClasses(Shift.class)
                .withConstraintProviderClass(EmployeeSchedulingConstraintProvider.class)
                .withTerminationConfig(new TerminationConfig()
                        .withScoreCalculationCountLimit(OPTAPLANNER_EVALUATIONS))
                .withMoveThreadCount("NONE")
                .withEnvironmentMode(EnvironmentMode.REPRODUCIBLE)
                .withRandomSeed(SEED))
                .buildSolver();
    }

    private static int changedShiftCount(RosterSolution left, RosterSolution right) {
        int count = 0;
        for (int shiftIndex = 0; shiftIndex < left.shiftCount(); shiftIndex++) {
            if (left.employeeIndex(shiftIndex) != right.employeeIndex(shiftIndex)) {
                count++;
            }
        }
        return count;
    }

    private static void printFairness(
            String label, PlanningProblem problem, ScoreCalculationResult breakdown) {
        System.out.printf(
                "%s_FAIRNESS_BREAKDOWN dayEvening=%d holiday=%d night=%d total=%d employees=%d shifts=%d%n",
                label,
                magnitude(breakdown, ConstraintIds.DAY_EVENING_FAIRNESS),
                magnitude(breakdown, ConstraintIds.HOLIDAY_FAIRNESS),
                magnitude(breakdown, ConstraintIds.NIGHT_FAIRNESS),
                -breakdown.score().softScore(2),
                problem.employeeCount(),
                problem.shiftCount());
    }

    private static int magnitude(ScoreCalculationResult breakdown, String constraintId) {
        return -breakdown.contributionByConstraintId().getOrDefault(constraintId, 0);
    }
}
