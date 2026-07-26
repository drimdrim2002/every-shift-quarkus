package org.acme.solver.score;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
import org.acme.solver.optaplanner.OptaPlannerScoreAdapter;
import org.acme.test.JsonLoader;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.optaplanner.core.api.score.ScoreExplanation;
import org.optaplanner.core.api.score.buildin.bendable.BendableScore;
import org.optaplanner.core.api.solver.SolutionManager;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.config.solver.SolverConfig;

import com.fasterxml.jackson.databind.ObjectMapper;

class FullScoreCalculatorDifferentialTest {

    private static final RosterScore ZERO = RosterScore.of(0, 0, 0, 0, 0);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final EmployeeScheduleBuilder scheduleBuilder = new EmployeeScheduleBuilder();
    private final PlanningProblemMapper problemMapper = new PlanningProblemMapper();
    private final EmployeeScheduleProjection projection = new EmployeeScheduleProjection();
    private final FullScoreCalculator calculator = new FullScoreCalculator();
    private final SolutionManager<EmployeeSchedule, BendableScore> solutionManager = SolutionManager.create(
            SolverFactory.<EmployeeSchedule>create(new SolverConfig()
                    .withSolutionClass(EmployeeSchedule.class)
                    .withEntityClasses(Shift.class)
                    .withConstraintProviderClass(EmployeeSchedulingConstraintProvider.class)));

    @ParameterizedTest(name = "{0} 동일 complete assignment")
    @ValueSource(strings = { "fairness.json", "preceptor.json", "request.json", "sample.json" })
    void 운영입력의_동일_assignment에서_OptaPlanner와_POJO_점수가_constraint별로_일치한다(
            String dataset) throws Exception {
        PlanningRequest request = objectMapper.readValue(
                JsonLoader.loadAsString("/json/" + dataset), PlanningRequest.class);
        PlanningProblem problem = problemMapper.toPlanningProblem(scheduleBuilder.build(request));
        int[] assignments = deterministicCompleteAssignment(problem);
        RosterSolution roster = new RosterSolution(problem.employeeCount(), assignments, ZERO);
        EmployeeSchedule schedule = projection.toEmployeeSchedule(problem, roster);

        BendableScore optaPlannerScore = solutionManager.update(schedule);
        ScoreExplanation<EmployeeSchedule, BendableScore> explanation = solutionManager.explain(schedule);
        ScoreCalculationResult pojo = calculator.calculateWithBreakdown(problem, roster);

        assertEquals(
                OptaPlannerScoreAdapter.toRosterScore(optaPlannerScore),
                pojo.score(),
                () -> mismatchMessage(dataset, problem, explanation, pojo));

        Map<String, RosterScore> optaByConstraint = optaScoresByConstraint(explanation);
        for (String constraintId : ConstraintIds.ALL) {
            assertEquals(
                    optaByConstraint.getOrDefault(constraintId, ZERO),
                    pojo.scoreByConstraintId().getOrDefault(constraintId, ZERO),
                    () -> mismatchMessage(dataset, problem, explanation, pojo));
        }
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

    private static Map<String, RosterScore> optaScoresByConstraint(
            ScoreExplanation<EmployeeSchedule, BendableScore> explanation) {
        Map<String, RosterScore> result = new LinkedHashMap<>();
        explanation.getConstraintMatchTotalMap().values().forEach(total -> result.put(
                total.getConstraintName(), OptaPlannerScoreAdapter.toRosterScore(total.getScore())));
        return result;
    }

    private static String mismatchMessage(
            String dataset,
            PlanningProblem problem,
            ScoreExplanation<EmployeeSchedule, BendableScore> explanation,
            ScoreCalculationResult pojo) {
        Map<String, RosterScore> opta = optaScoresByConstraint(explanation);
        List<String> mismatches = ConstraintIds.ALL.stream()
                .filter(id -> !opta.getOrDefault(id, ZERO)
                        .equals(pojo.scoreByConstraintId().getOrDefault(id, ZERO)))
                .map(id -> {
                    String affected = pojo.contributions(id).stream()
                            .map(contribution -> contribution.describe(problem))
                            .limit(10)
                            .collect(Collectors.joining("; "));
                    return id + " opta=" + opta.getOrDefault(id, ZERO)
                            + " pojo=" + pojo.scoreByConstraintId().getOrDefault(id, ZERO)
                            + " affected=[" + affected + "]";
                })
                .toList();
        return dataset + " differential mismatch: " + String.join(" | ", mismatches);
    }
}
