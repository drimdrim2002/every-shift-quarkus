package org.acme.solver.move;

import java.util.Arrays;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.EmployeeScheduleBuilder;
import org.acme.solver.adapter.PlanningProblemMapper;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.score.FullScoreCalculator;
import org.acme.solver.score.IncrementalScoreCalculator;
import org.acme.test.JsonLoader;

import com.fasterxml.jackson.databind.ObjectMapper;

final class Phase4TestSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private Phase4TestSupport() {
    }

    static Fixture fixture(String dataset) {
        try {
            PlanningRequest request = OBJECT_MAPPER.readValue(
                    JsonLoader.loadAsString("/json/" + dataset), PlanningRequest.class);
            PlanningProblem problem = new PlanningProblemMapper().toPlanningProblem(
                    new EmployeeScheduleBuilder().build(request));
            int[] assignments = problem.initialEmployeeIndexByShift();
            for (int shiftIndex = 0; shiftIndex < assignments.length; shiftIndex++) {
                if (assignments[shiftIndex] < 0) {
                    assignments[shiftIndex] = Math.floorMod(shiftIndex * 7 + 3, problem.employeeCount());
                }
            }
            FullScoreCalculator full = new FullScoreCalculator();
            RosterSolution unscored = new RosterSolution(
                    problem.employeeCount(), assignments, RosterScore.of(0, 0, 0, 0, 0));
            RosterSolution initial = new RosterSolution(
                    problem.employeeCount(), assignments, full.calculateScore(problem, unscored));
            SearchState state = new SearchState(problem, initial);
            IncrementalScoreCalculator incremental = new IncrementalScoreCalculator(problem, initial, full);
            return new Fixture(problem, full, initial, state, incremental);
        } catch (Exception exception) {
            throw new IllegalStateException(dataset + " fixture 생성 실패", exception);
        }
    }

    static int differentEmployee(PlanningProblem problem, int employeeIndex, int salt) {
        if (problem.employeeCount() < 2) {
            throw new IllegalArgumentException("두 명 이상의 직원이 필요합니다.");
        }
        int candidate = Math.floorMod(employeeIndex + 1 + salt, problem.employeeCount());
        return candidate == employeeIndex ? (candidate + 1) % problem.employeeCount() : candidate;
    }

    static int mutableShift(PlanningProblem problem, int... excluded) {
        for (int shiftIndex : problem.mutableShiftIndexes()) {
            boolean matches = false;
            for (int excludedShift : excluded) {
                if (shiftIndex == excludedShift) {
                    matches = true;
                    break;
                }
            }
            if (!matches) {
                return shiftIndex;
            }
        }
        throw new IllegalStateException("사용 가능한 mutable shift가 없습니다: " + Arrays.toString(excluded));
    }

    record Fixture(
            PlanningProblem problem,
            FullScoreCalculator full,
            RosterSolution initial,
            SearchState state,
            IncrementalScoreCalculator incremental) {
    }
}
