package org.acme.solver.score;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.EmployeeScheduleBuilder;
import org.acme.model.EmployeeSchedule;
import org.acme.solver.adapter.EmployeeScheduleProjection;
import org.acme.solver.adapter.PlanningProblemMapper;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.test.JsonLoader;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;

class OperationalScoreContractTest {

    private static final RosterScore ZERO = RosterScore.of(0, 0, 0, 0, 0);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final EmployeeScheduleBuilder scheduleBuilder = new EmployeeScheduleBuilder();
    private final PlanningProblemMapper problemMapper = new PlanningProblemMapper();
    private final EmployeeScheduleProjection projection = new EmployeeScheduleProjection();
    private final FullScoreCalculator calculator = new FullScoreCalculator();

    @ParameterizedTest(name = "{0} POJO evaluator contract")
    @ValueSource(strings = { "fairness.json", "preceptor.json", "request.json", "sample.json" })
    void 운영입력의_동일_assignment는_결정론적_점수와_projection_roundtrip을_유지한다(
            String dataset) throws Exception {
        PlanningRequest request = objectMapper.readValue(
                JsonLoader.loadAsString("/json/" + dataset), PlanningRequest.class);
        PlanningProblem problem = problemMapper.toPlanningProblem(scheduleBuilder.build(request));
        int[] assignments = deterministicCompleteAssignment(problem);
        RosterSolution input = new RosterSolution(problem.employeeCount(), assignments, ZERO);

        ScoreCalculationResult first = calculator.calculateWithBreakdown(problem, input);
        ScoreCalculationResult second = calculator.calculateWithBreakdown(problem, input);
        RosterSolution scored = new RosterSolution(problem.employeeCount(), assignments, first.score());

        assertEquals(first.score(), second.score());
        assertEquals(first.contributionByConstraintId(), second.contributionByConstraintId());
        assertEquals(first.score(), sumConstraintVectors(first));

        EmployeeSchedule external = projection.toEmployeeSchedule(problem, scored);
        RosterSolution roundTrip = projection.toRosterSolution(problem, external);
        assertArrayEquals(assignments, roundTrip.employeeIndexByShift());
        assertEquals(first.score(), roundTrip.score());
        assertEquals(RosterScore.class, EmployeeSchedule.class.getDeclaredField("score").getType());
    }

    private static int[] deterministicCompleteAssignment(PlanningProblem problem) {
        int[] assignments = problem.initialEmployeeIndexByShift();
        for (int shiftIndex = 0; shiftIndex < assignments.length; shiftIndex++) {
            if (assignments[shiftIndex] < 0) {
                assignments[shiftIndex] = Math.floorMod(shiftIndex * 7 + 3, problem.employeeCount());
            }
        }
        return assignments;
    }

    private static RosterScore sumConstraintVectors(ScoreCalculationResult result) {
        int hard = 0;
        int[] soft = new int[RosterScore.SOFT_LEVELS];
        for (RosterScore score : result.scoreByConstraintId().values()) {
            hard = Math.addExact(hard, score.hardScore());
            for (int index = 0; index < soft.length; index++) {
                soft[index] = Math.addExact(soft[index], score.softScore(index));
            }
        }
        return new RosterScore(hard, soft);
    }
}
