package org.acme.solver.optaplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.EmployeeScheduleBuilder;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.acme.solver.adapter.PlanningProblemMapper;
import org.acme.solver.algorithm.EmployeeSchedulingConstraintProvider;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.test.JsonLoader;
import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.config.solver.EnvironmentMode;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class OptaPlannerEngineCompatibilityTest {

    private static final long SEED = 42L;
    private static final long SCORE_CALCULATION_LIMIT = 20_000L;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    EmployeeScheduleBuilder scheduleBuilder;

    @Inject
    PlanningProblemMapper problemMapper;

    @Inject
    OptaPlannerSolverEngine engine;

    @Test
    void 엔진_경계_도입_후에도_고정_평가_기준선과_점수_및_assignment가_같다() throws Exception {
        OptaPlannerSolverEngine actualEngine = ClientProxy.unwrap(engine);
        PlanningRequest request = objectMapper.readValue(
                JsonLoader.loadAsString("/json/fairness_test.json"),
                PlanningRequest.class);

        EmployeeSchedule baseline = directSolver(
                actualEngine.moveThreadCount,
                EnvironmentMode.valueOf(actualEngine.environmentMode.trim().toUpperCase()))
                .solve(scheduleBuilder.build(request));
        PlanningProblem problem = problemMapper.toPlanningProblem(scheduleBuilder.build(request));
        SolveResult<RosterSolution> adapted = actualEngine.solve(
                problem,
                SolveOptions.builder()
                        .maxEvaluations(SCORE_CALCULATION_LIMIT)
                        .randomSeed(SEED)
                        .build(),
                solution -> {
                });

        assertEquals(OptaPlannerScoreAdapter.toRosterScore(baseline.getScore()), adapted.score());
        assertEquals(assignmentsByShiftId(baseline), assignmentsByShiftId(problem, adapted.bestSolution()));
    }

    private static org.optaplanner.core.api.solver.Solver<EmployeeSchedule> directSolver(
            String moveThreadCount,
            EnvironmentMode environmentMode) {
        TerminationConfig termination = new TerminationConfig()
                .withScoreCalculationCountLimit(SCORE_CALCULATION_LIMIT);
        return SolverFactory.<EmployeeSchedule>create(new SolverConfig()
                .withSolutionClass(EmployeeSchedule.class)
                .withEntityClasses(Shift.class)
                .withConstraintProviderClass(EmployeeSchedulingConstraintProvider.class)
                .withTerminationConfig(termination)
                .withMoveThreadCount(moveThreadCount)
                .withEnvironmentMode(environmentMode)
                .withRandomSeed(SEED))
                .buildSolver();
    }

    private static Map<Long, String> assignmentsByShiftId(EmployeeSchedule schedule) {
        Map<Long, String> assignments = new LinkedHashMap<>();
        for (Shift shift : schedule.getShiftList()) {
            assignments.put(shift.getId(), shift.getEmployee().getId());
        }
        return assignments;
    }

    private static Map<Long, String> assignmentsByShiftId(
            PlanningProblem problem,
            RosterSolution solution) {
        Map<Long, String> assignments = new LinkedHashMap<>();
        for (int shiftIndex = 0; shiftIndex < problem.shiftCount(); shiftIndex++) {
            assignments.put(
                    problem.shifts().get(shiftIndex).planningId(),
                    problem.employees().get(solution.employeeIndex(shiftIndex)).externalId());
        }
        return assignments;
    }
}
