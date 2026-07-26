package org.acme.solver.alns;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.move.SearchState;
import org.acme.solver.score.FullScoreCalculator;
import org.acme.solver.score.IncrementalScoreCalculator;

final class AlnsTestSupport {

    private AlnsTestSupport() {
    }

    static Fixture relationFixture() {
        PlanningProblem.ScheduleWindow window = new PlanningProblem.ScheduleWindow(
                "tenant", "alns", 0, 10,
                LocalDate.of(2026, 1, 1), LocalDate.of(2025, 12, 31));
        List<PlanningProblem.EmployeeData> employees = List.of(
                employee("e0", null, Set.of("RN")),
                employee("e1", "e0", Set.of("RN")),
                employee("e2", null, Set.of("RN")),
                employee("e3", null, Set.of("RN")));
        List<PlanningProblem.ShiftData> shifts = List.of(
                shift(0, LocalDateTime.of(2025, 12, 31, 8, 0), "D", 3, false),
                shift(1, LocalDateTime.of(2026, 1, 1, 8, 0), "D", 0, false),
                shift(2, LocalDateTime.of(2026, 1, 1, 8, 0), "D", 1, false),
                shift(3, LocalDateTime.of(2026, 1, 1, 8, 0), "D", 2, false),
                shift(4, LocalDateTime.of(2026, 1, 2, 8, 0), "D", 2, false),
                shift(5, LocalDateTime.of(2026, 1, 2, 8, 0), "D", 3, false),
                shift(6, LocalDateTime.of(2026, 1, 3, 20, 0), "N", 0, false),
                shift(7, LocalDateTime.of(2026, 1, 3, 20, 0), "N", 1, false),
                shift(8, LocalDateTime.of(2026, 1, 4, 8, 0), "D", 2, true));
        return fixture(new PlanningProblem(window, employees, shifts, List.of()));
    }

    static Fixture skillFixture(boolean initiallySkilled) {
        PlanningProblem.ScheduleWindow window = new PlanningProblem.ScheduleWindow(
                "tenant", "skill", 0, 2,
                LocalDate.of(2026, 1, 1), LocalDate.of(2025, 12, 31));
        List<PlanningProblem.EmployeeData> employees = List.of(
                employee("skilled", null, Set.of("RN")),
                employee("unskilled", null, Set.of("ASSISTANT")));
        PlanningProblem problem = new PlanningProblem(
                window,
                employees,
                List.of(shift(
                        0, LocalDateTime.of(2026, 1, 1, 8, 0), "D",
                        initiallySkilled ? 0 : 1, false)),
                List.of());
        return fixture(problem);
    }

    static Fixture fairnessFixture() {
        PlanningProblem.ScheduleWindow window = new PlanningProblem.ScheduleWindow(
                "tenant", "fairness-hotspot", 0, 30,
                LocalDate.of(2026, 1, 1), LocalDate.of(2025, 12, 31));
        List<PlanningProblem.EmployeeData> employees = List.of(
                fairnessEmployee("e0"),
                fairnessEmployee("e1"),
                fairnessEmployee("e2"),
                fairnessEmployee("e3"));
        List<PlanningProblem.ShiftData> shifts = List.of(
                shift(0, LocalDateTime.of(2026, 1, 1, 15, 0), "E", 0, false),
                shift(1, LocalDateTime.of(2026, 1, 4, 15, 0), "E", 0, false),
                shift(2, LocalDateTime.of(2026, 1, 7, 15, 0), "E", 0, false),
                shift(3, LocalDateTime.of(2026, 1, 10, 15, 0), "E", 1, false),
                shift(4, LocalDateTime.of(2026, 1, 13, 8, 0), "D", 2, false),
                shift(5, LocalDateTime.of(2026, 1, 16, 8, 0), "D", 3, false),
                shift(6, LocalDateTime.of(2026, 1, 19, 8, 0), "D", 3, false));
        return fixture(new PlanningProblem(window, employees, shifts, List.of()));
    }

    static Fixture fixture(PlanningProblem problem) {
        FullScoreCalculator full = new FullScoreCalculator();
        int[] assignments = problem.initialEmployeeIndexByShift();
        RosterSolution unscored = new RosterSolution(
                problem.employeeCount(), assignments, RosterScore.of(0, 0, 0, 0, 0));
        RosterSolution initial = new RosterSolution(
                problem.employeeCount(), assignments, full.calculateScore(problem, unscored));
        SearchState state = new SearchState(problem, initial);
        IncrementalScoreCalculator incremental = new IncrementalScoreCalculator(problem, initial, full);
        return new Fixture(problem, full, initial, state, incremental);
    }

    private static PlanningProblem.EmployeeData employee(
            String id, String preceptorId, Set<String> skills) {
        return new PlanningProblem.EmployeeData(
                id, id, skills, Set.of("D", "N"), 0, 0, 0, 1, preceptorId);
    }

    private static PlanningProblem.EmployeeData fairnessEmployee(String id) {
        return new PlanningProblem.EmployeeData(
                id, id, Set.of("RN"), Set.of("D", "E", "N"), 0, 0, 0, 1, null);
    }

    private static PlanningProblem.ShiftData shift(
            long id,
            LocalDateTime start,
            String code,
            int employeeIndex,
            boolean pinned) {
        return new PlanningProblem.ShiftData(
                id,
                "s" + id,
                code,
                start,
                start.plusHours(8),
                start.toLocalDate(),
                "ward",
                "RN",
                pinned,
                employeeIndex,
                "N".equals(code) ? 1 : 0,
                0);
    }

    record Fixture(
            PlanningProblem problem,
            FullScoreCalculator full,
            RosterSolution initial,
            SearchState state,
            IncrementalScoreCalculator incremental) {
    }
}
