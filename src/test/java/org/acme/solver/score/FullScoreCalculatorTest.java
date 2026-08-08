package org.acme.solver.score;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FullScoreCalculatorTest {

    private static final LocalDate BASE_DATE = LocalDate.of(2026, 3, 1);
    private static final RosterScore ZERO = RosterScore.of(0, 0, 0, 0, 0);

    private final FullScoreCalculator calculator = new FullScoreCalculator();

    @Test
    void 직원_실제일_논리일_shift_type_실제월_index를_만든다() {
        PlanningProblem problem = problem(
                List.of(employee("E1")),
                List.of(
                        shift(1, "N", BASE_DATE.atTime(0, 0), BASE_DATE.atTime(8, 0), 0),
                        shift(2, "D", BASE_DATE.plusDays(1).atTime(8, 0),
                                BASE_DATE.plusDays(1).atTime(16, 0), 0)),
                List.of());
        ScoreEvaluationContext context = new ScoreEvaluationContext(problem, solution(problem));

        assertEquals(List.of(0, 1), context.index().shiftsByEmployee(0));
        assertEquals(List.of(0), context.index().shiftsByActualDate(0, BASE_DATE));
        assertEquals(List.of(0), context.index().shiftsByLogicalDate(0, BASE_DATE.minusDays(1)));
        assertEquals(List.of(0), context.index().shiftsByType(0).get("N"));
        assertEquals(List.of(0, 1), context.index().shiftsByActualMonth(0).get(YearMonth.of(2026, 3)));
    }

    @Test
    void required_skill_overlap_하루한근무를_constraint별로_집계한다() {
        PlanningProblem.EmployeeData employee = employee("E1", Set.of("GENERAL"), null, 1);
        PlanningProblem problem = problem(
                List.of(employee),
                List.of(
                        shift(1, "D", BASE_DATE.atTime(8, 0), BASE_DATE.atTime(16, 0), 0,
                                false, "ICU", 0, 0),
                        shift(2, "D", BASE_DATE.atTime(15, 0), BASE_DATE.atTime(23, 0), 0,
                                false, "GENERAL", 0, 0)),
                List.of());

        ScoreCalculationResult result = calculator.calculateWithBreakdown(problem, solution(problem));

        // fairness(day/evening)는 soft[1]
        assertEquals(RosterScore.of(-62, 0, -4, 0, 0), result.score());
        assertEquals(-1, result.contributionByConstraintId().get(ConstraintIds.REQUIRED_SKILL));
        assertEquals(-60, result.contributionByConstraintId().get(ConstraintIds.OVERLAP));
        assertEquals(-1, result.contributionByConstraintId().get(ConstraintIds.ONE_SHIFT_PER_DAY));
    }

    @ParameterizedTest
    @CsvSource({ "719,-1", "720,0", "721,0" })
    void 최소_12시간_경계를_분단위로_평가한다(int restMinutes, int expectedHard) {
        LocalDateTime firstEnd = BASE_DATE.atTime(16, 0);
        PlanningProblem problem = problem(
                List.of(employee("E1")),
                List.of(
                        shift(1, "D", firstEnd.minusHours(8), firstEnd, 0),
                        shift(2, "D", firstEnd.plusMinutes(restMinutes),
                                firstEnd.plusMinutes(restMinutes).plusHours(8), 0)),
                List.of());

        ScoreCalculationResult result = calculator.calculateWithBreakdown(problem, solution(problem));

        assertEquals(expectedHard,
                result.scoreByConstraintId().getOrDefault(ConstraintIds.MINIMUM_REST, ZERO).hardScore());
    }

    @ParameterizedTest
    @CsvSource({ "1919,-1", "1920,0", "1921,0" })
    void 야간후_32시간_경계와_pinned_hard_포함을_평가한다(int restMinutes, int expectedHard) {
        LocalDateTime nightEnd = BASE_DATE.atTime(8, 0);
        PlanningProblem problem = problem(
                List.of(employee("E1")),
                List.of(
                        shift(1, "N", BASE_DATE.atTime(0, 0), nightEnd, 0,
                                true, "ALL", 1, 0),
                        shift(2, "D", nightEnd.plusMinutes(restMinutes),
                                nightEnd.plusMinutes(restMinutes).plusHours(8), 0)),
                List.of());

        ScoreCalculationResult result = calculator.calculateWithBreakdown(problem, solution(problem));

        assertEquals(expectedHard,
                result.scoreByConstraintId().getOrDefault(ConstraintIds.NIGHT_TO_DAY_REST, ZERO).hardScore());
    }

    @Test
    void 야간후_32시간은_모든_D가_아니라_가장_이른_다음_D만_hard로_평가한다() {
        LocalDateTime nightEnd = BASE_DATE.atTime(8, 0);
        PlanningProblem problem = problem(
                List.of(employee("E1")),
                List.of(
                        shift(1, "N", BASE_DATE.atTime(0, 0), nightEnd, 0),
                        shift(2, "D", nightEnd.plusHours(16), nightEnd.plusHours(24), 0),
                        shift(3, "D", nightEnd.plusHours(48), nightEnd.plusHours(56), 0)),
                List.of());

        ScoreCalculationResult result = calculator.calculateWithBreakdown(problem, solution(problem));

        assertEquals(-960, result.scoreByConstraintId().get(ConstraintIds.NIGHT_TO_DAY_REST).hardScore());
        assertEquals(1, result.contributions(ConstraintIds.NIGHT_TO_DAY_REST).size());
        assertEquals(List.of(0, 1), result.contributions(ConstraintIds.NIGHT_TO_DAY_REST).getFirst().shiftIndexes());
        assertEquals(ScoreLevel.HARD, result.contributions(ConstraintIds.NIGHT_TO_DAY_REST).getFirst().level());
    }

    @ParameterizedTest
    @CsvSource({ "2879,-1", "2880,0", "2881,0" })
    void 연속야간후_48시간_경계를_위반당_1점으로_평가한다(int restMinutes, int expectedHard) {
        LocalDateTime secondEnd = BASE_DATE.plusDays(1).atTime(8, 0);
        PlanningProblem problem = problem(
                List.of(employee("E1")),
                List.of(
                        shift(1, "N", BASE_DATE.atTime(0, 0), BASE_DATE.atTime(8, 0), 0),
                        shift(2, "N", BASE_DATE.plusDays(1).atTime(0, 0), secondEnd, 0),
                        shift(3, "D", secondEnd.plusMinutes(restMinutes),
                                secondEnd.plusMinutes(restMinutes).plusHours(8), 0)),
                List.of());

        ScoreCalculationResult result = calculator.calculateWithBreakdown(problem, solution(problem));

        assertEquals(expectedHard,
                result.scoreByConstraintId().getOrDefault(ConstraintIds.POST_NIGHT_RECOVERY, ZERO).hardScore());
    }

    @Test
    void 세번째_연속야간이_있으면_48시간_기준점을_세번째_종료로_미룬다() {
        LocalDateTime thirdEnd = BASE_DATE.plusDays(2).atTime(8, 0);
        PlanningProblem problem = problem(
                List.of(employee("E1")),
                List.of(
                        shift(1, "N", BASE_DATE.atTime(0, 0), BASE_DATE.atTime(8, 0), 0),
                        shift(2, "N", BASE_DATE.plusDays(1).atTime(0, 0), BASE_DATE.plusDays(1).atTime(8, 0), 0),
                        shift(3, "N", BASE_DATE.plusDays(2).atTime(0, 0), thirdEnd, 0),
                        shift(4, "D", thirdEnd.plusMinutes(2879), thirdEnd.plusMinutes(2879).plusHours(8), 0)),
                List.of());

        ScoreCalculationResult result = calculator.calculateWithBreakdown(problem, solution(problem));

        assertEquals(-1, result.scoreByConstraintId().get(ConstraintIds.POST_NIGHT_RECOVERY).hardScore());
        assertEquals(1, result.contributions(ConstraintIds.POST_NIGHT_RECOVERY).size());
        assertEquals(List.of(2, 3), result.contributions(ConstraintIds.POST_NIGHT_RECOVERY).getFirst().shiftIndexes());
    }

    @Test
    void 네번_연속야간과_pinned_shift를_모두_hard에_포함한다() {
        List<PlanningProblem.ShiftData> shifts = new ArrayList<>();
        for (int day = 0; day < 4; day++) {
            shifts.add(shift(day + 1, "N", BASE_DATE.plusDays(day).atTime(0, 0),
                    BASE_DATE.plusDays(day).atTime(8, 0), 0,
                    day == 0, "ALL", 1, 0));
        }
        PlanningProblem problem = problem(List.of(employee("E1")), shifts, List.of());

        ScoreCalculationResult result = calculator.calculateWithBreakdown(problem, solution(problem));

        assertEquals(-1, result.scoreByConstraintId().get(ConstraintIds.CONSECUTIVE_NIGHT).hardScore());
    }

    @Test
    void 논리일_gap이_있으면_네개의_야간이어도_연속야간이_아니다() {
        PlanningProblem problem = problem(
                List.of(employee("E1")),
                List.of(
                        shift(1, "N", BASE_DATE.atTime(0, 0), BASE_DATE.atTime(8, 0), 0),
                        shift(2, "N", BASE_DATE.plusDays(1).atTime(0, 0), BASE_DATE.plusDays(1).atTime(8, 0), 0),
                        shift(3, "N", BASE_DATE.plusDays(3).atTime(0, 0), BASE_DATE.plusDays(3).atTime(8, 0), 0),
                        shift(4, "N", BASE_DATE.plusDays(4).atTime(0, 0), BASE_DATE.plusDays(4).atTime(8, 0), 0)),
                List.of());

        ScoreCalculationResult result = calculator.calculateWithBreakdown(problem, solution(problem));

        assertFalse(result.scoreByConstraintId().containsKey(ConstraintIds.CONSECUTIVE_NIGHT));
    }

    @Test
    void 월간_야간은_논리일이_아닌_실제_시작월로_16번째부터_평가한다() {
        List<PlanningProblem.ShiftData> shifts = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            LocalDate date = BASE_DATE.plusDays(index * 2L);
            shifts.add(shift(index + 1, "N", date.atTime(0, 0), date.atTime(8, 0), 0));
        }
        PlanningProblem problem = problem(List.of(employee("E1")), shifts, List.of());

        ScoreCalculationResult result = calculator.calculateWithBreakdown(problem, solution(problem));

        assertEquals(-1, result.scoreByConstraintId().get(ConstraintIds.MONTHLY_NIGHT_LIMIT).hardScore());
    }

    @Test
    void preceptor_preceptee_불일치는_기존_양방향_constraint를_각각_평가한다() {
        PlanningProblem.EmployeeData preceptor = employee("P");
        PlanningProblem.EmployeeData preceptee = employee("T", Set.of("ALL"), "P", 1);
        PlanningProblem problem = problem(
                List.of(preceptor, preceptee),
                List.of(
                        shift(1, "E", BASE_DATE.atTime(16, 0), BASE_DATE.plusDays(1).atTime(0, 0), 0),
                        shift(2, "D", BASE_DATE.atTime(8, 0), BASE_DATE.atTime(16, 0), 1)),
                List.of());

        ScoreCalculationResult result = calculator.calculateWithBreakdown(problem, solution(problem));

        assertEquals(-1, result.scoreByConstraintId().get(ConstraintIds.PRECEPTEE_PAIR).hardScore());
        assertEquals(-1, result.scoreByConstraintId().get(ConstraintIds.PRECEPTOR_PAIR).hardScore());
        assertTrue(result.contributions(ConstraintIds.PRECEPTEE_PAIR).getFirst().employeeIndexes().containsAll(List.of(0, 1)));
    }

    @Test
    void preceptor_preceptee가_같은_실제일과_shift_code로_근무하면_위반이_없다() {
        PlanningProblem.EmployeeData preceptor = employee("P");
        PlanningProblem.EmployeeData preceptee = employee("T", Set.of("ALL"), "P", 1);
        PlanningProblem problem = problem(
                List.of(preceptor, preceptee),
                List.of(
                        shift(1, "D", BASE_DATE.atTime(8, 0), BASE_DATE.atTime(16, 0), 0),
                        shift(2, "D", BASE_DATE.atTime(8, 0), BASE_DATE.atTime(16, 0), 1)),
                List.of());

        ScoreCalculationResult result = calculator.calculateWithBreakdown(problem, solution(problem));

        assertFalse(result.scoreByConstraintId().containsKey(ConstraintIds.PRECEPTEE_PAIR));
        assertFalse(result.scoreByConstraintId().containsKey(ConstraintIds.PRECEPTOR_PAIR));
    }

    @Test
    void 관계_상대의_shift가_없으면_해당_방향을_각각_위반한다() {
        PlanningProblem.EmployeeData preceptor = employee("P");
        PlanningProblem.EmployeeData preceptee = employee("T", Set.of("ALL"), "P", 1);

        PlanningProblem onlyPreceptee = problem(
                List.of(preceptor, preceptee),
                List.of(shift(1, "D", BASE_DATE.atTime(8, 0), BASE_DATE.atTime(16, 0), 1)),
                List.of());
        PlanningProblem onlyPreceptor = problem(
                List.of(preceptor, preceptee),
                List.of(shift(1, "D", BASE_DATE.atTime(8, 0), BASE_DATE.atTime(16, 0), 0)),
                List.of());

        ScoreCalculationResult precepteeResult = calculator.calculateWithBreakdown(
                onlyPreceptee, solution(onlyPreceptee));
        ScoreCalculationResult preceptorResult = calculator.calculateWithBreakdown(
                onlyPreceptor, solution(onlyPreceptor));

        assertEquals(-1, precepteeResult.scoreByConstraintId().get(ConstraintIds.PRECEPTEE_PAIR).hardScore());
        assertEquals(-1, preceptorResult.scoreByConstraintId().get(ConstraintIds.PRECEPTOR_PAIR).hardScore());
    }

    @Test
    void undesired는_실제일과_논리일_중복을_shift당_한번만_집계하고_pinned는_제외한다() {
        PlanningProblem.EmployeeData employee = employee("E1", Set.of("ALL"), null, 4);
        PlanningProblem.ShiftData night = shift(1, "N", BASE_DATE.atTime(0, 0), BASE_DATE.atTime(8, 0), 0);
        PlanningProblem.ShiftData pinnedDay = shift(2, "D", BASE_DATE.plusDays(2).atTime(8, 0),
                BASE_DATE.plusDays(2).atTime(16, 0), 0, true, "ALL", 0, 0);
        PlanningProblem problem = problem(
                List.of(employee),
                List.of(night, pinnedDay),
                List.of(
                        availability(1, 0, BASE_DATE.minusDays(1), PlanningProblem.AvailabilityKind.UNDESIRED),
                        availability(2, 0, BASE_DATE, PlanningProblem.AvailabilityKind.UNDESIRED),
                        availability(3, 0, BASE_DATE, PlanningProblem.AvailabilityKind.UNDESIRED),
                        availability(4, 0, BASE_DATE.plusDays(2), PlanningProblem.AvailabilityKind.UNDESIRED)));

        ScoreCalculationResult result = calculator.calculateWithBreakdown(problem, solution(problem));

        assertEquals(-1920, result.scoreByConstraintId().get(ConstraintIds.UNDESIRED).softScore(0));
        assertEquals(1, result.contributions(ConstraintIds.UNDESIRED).size());
        assertEquals(List.of(0), result.contributions(ConstraintIds.UNDESIRED).getFirst().shiftIndexes());
    }

    @ParameterizedTest
    @CsvSource({ "5,59,-480", "6,0,0" })
    void undesired_논리일은_야간_06시_cutoff를_정확히_적용한다(
            int startHour, int startMinute, int expectedSoft) {
        LocalDate actualDate = BASE_DATE.plusDays(1);
        LocalDateTime start = actualDate.atTime(startHour, startMinute);
        PlanningProblem problem = problem(
                List.of(employee("E1")),
                List.of(shift(1, "N", start, start.plusHours(8), 0)),
                List.of(availability(
                        1, 0, BASE_DATE, PlanningProblem.AvailabilityKind.UNDESIRED)));

        ScoreCalculationResult result = calculator.calculateWithBreakdown(problem, solution(problem));

        assertEquals(expectedSoft,
                result.scoreByConstraintId().getOrDefault(ConstraintIds.UNDESIRED, ZERO).softScore(0));
    }

    @Test
    void desired는_pinned도_포함하되_논리일이_아닌_실제_시작일만_reward한다() {
        PlanningProblem problem = problem(
                List.of(employee("E1")),
                List.of(shift(1, "N", BASE_DATE.atTime(0, 0), BASE_DATE.atTime(8, 0), 0,
                        true, "ALL", 1, 0)),
                List.of(
                        availability(1, 0, BASE_DATE.minusDays(1), PlanningProblem.AvailabilityKind.DESIRED),
                        availability(2, 0, BASE_DATE, PlanningProblem.AvailabilityKind.DESIRED)));

        ScoreCalculationResult result = calculator.calculateWithBreakdown(problem, solution(problem));

        assertEquals(480, result.scoreByConstraintId().get(ConstraintIds.DESIRED).softScore(2));
    }

    @Test
    void fairness는_직원별_burden합과_shift_type_count를_각각_제곱한다() {
        PlanningProblem problem = problem(
                List.of(employee("E1")),
                List.of(
                        shift(1, "N", BASE_DATE.atTime(0, 0), BASE_DATE.atTime(8, 0), 0,
                                true, "ALL", 2, 4),
                        shift(2, "N", BASE_DATE.plusDays(2).atTime(0, 0), BASE_DATE.plusDays(2).atTime(8, 0), 0,
                                false, "ALL", 3, 1),
                        shift(3, "D", BASE_DATE.plusDays(4).atTime(8, 0), BASE_DATE.plusDays(4).atTime(16, 0), 0),
                        shift(4, "D", BASE_DATE.plusDays(6).atTime(8, 0), BASE_DATE.plusDays(6).atTime(16, 0), 0),
                        shift(5, "E", BASE_DATE.plusDays(8).atTime(16, 0), BASE_DATE.plusDays(9).atTime(0, 0), 0),
                        shift(6, "E", BASE_DATE.plusDays(10).atTime(16, 0), BASE_DATE.plusDays(11).atTime(0, 0), 0)),
                List.of());

        ScoreCalculationResult result = calculator.calculateWithBreakdown(problem, solution(problem));

        assertEquals(-25, result.scoreByConstraintId().get(ConstraintIds.NIGHT_FAIRNESS).softScore(1));
        assertEquals(-25, result.scoreByConstraintId().get(ConstraintIds.HOLIDAY_FAIRNESS).softScore(1));
        assertEquals(-24, result.scoreByConstraintId().get(ConstraintIds.DAY_EVENING_FAIRNESS).softScore(1));
        assertEquals(-74, result.score().softScore(1));
    }

    @Test
    void constraint별_contribution_벡터합이_최종_score와_일치하고_영향_ID를_진단한다() {
        PlanningProblem problem = problem(
                List.of(employee("E1", Set.of("GENERAL"), null, 1)),
                List.of(shift(99, "D", BASE_DATE.atTime(8, 0), BASE_DATE.atTime(16, 0), 0,
                        false, "ICU", 0, 0)),
                List.of());

        ScoreCalculationResult result = calculator.calculateWithBreakdown(problem, solution(problem));
        RosterScore summed = result.scoreByConstraintId().values().stream()
                .reduce(ZERO, FullScoreCalculatorTest::add);

        assertEquals(result.score(), summed);
        String diagnostic = result.contributions(ConstraintIds.REQUIRED_SKILL).getFirst().describe(problem);
        assertTrue(diagnostic.contains("E1"));
        assertTrue(diagnostic.contains("99"));
        assertTrue(diagnostic.contains(ConstraintIds.REQUIRED_SKILL));
    }

    @Test
    void null_shape_mismatch와_미배정_assignment를_거절한다() {
        PlanningProblem problem = problem(
                List.of(employee("E1")),
                List.of(shift(1, "D", BASE_DATE.atTime(8, 0), BASE_DATE.atTime(16, 0), 0)),
                List.of());

        assertThrows(NullPointerException.class, () -> calculator.calculateScore(null, solution(problem)));
        assertThrows(NullPointerException.class, () -> calculator.calculateScore(problem, null));
        assertThrows(IllegalArgumentException.class,
                () -> new RosterSolution(1, new int[] { -1 }, ZERO));
        RosterSolution wrongShape = new RosterSolution(1, new int[0], ZERO);
        assertThrows(IllegalArgumentException.class, () -> calculator.calculateScore(problem, wrongShape));
    }

    @Test
    void evaluator_목록에는_Phase3의_12개_전체평가기만_존재한다() {
        assertEquals(List.of(
                "required-skill", "overlap", "minimum-rest", "consecutive-night",
                "monthly-night-limit", "one-shift-per-day", "preceptor-pair",
                "post-night-recovery", "night-to-day-rest", "undesired-assignment",
                "fairness", "desired-assignment"),
                calculator.evaluators().stream().map(ConstraintEvaluator::evaluatorId).toList());
        assertFalse(calculator.evaluators().isEmpty());
    }

    private static RosterScore add(RosterScore left, RosterScore right) {
        return RosterScore.of(
                Math.addExact(left.hardScore(), right.hardScore()),
                Math.addExact(left.softScore(0), right.softScore(0)),
                Math.addExact(left.softScore(1), right.softScore(1)),
                Math.addExact(left.softScore(2), right.softScore(2)),
                Math.addExact(left.softScore(3), right.softScore(3)));
    }

    private static PlanningProblem problem(
            List<PlanningProblem.EmployeeData> employees,
            List<PlanningProblem.ShiftData> shifts,
            List<PlanningProblem.AvailabilityData> availabilities) {
        return new PlanningProblem(
                new PlanningProblem.ScheduleWindow(
                        "tenant", "schedule", 0, 365,
                        LocalDate.of(2020, 1, 1), LocalDate.of(2019, 12, 31)),
                employees, shifts, availabilities);
    }

    private static PlanningProblem.EmployeeData employee(String id) {
        return employee(id, Set.of("ALL"), null, 1);
    }

    private static PlanningProblem.EmployeeData employee(
            String id, Set<String> skills, String preceptorId, int penaltyWeight) {
        return new PlanningProblem.EmployeeData(
                id, id, skills, Set.of("D", "E", "N"),
                0, 0, 0, penaltyWeight, preceptorId);
    }

    private static PlanningProblem.ShiftData shift(
            long id, String code, LocalDateTime start, LocalDateTime end, int employeeIndex) {
        return shift(id, code, start, end, employeeIndex, false, "ALL",
                "N".equals(code) ? 1 : 0, 0);
    }

    private static PlanningProblem.ShiftData shift(
            long id, String code, LocalDateTime start, LocalDateTime end, int employeeIndex,
            boolean pinned, String requiredSkill, int nightBurden, int holidayBurden) {
        return new PlanningProblem.ShiftData(
                id, "S" + id, code, start, end,
                org.acme.solver.ShiftDateMatcher.resolveLogicalDate(start, code),
                "location", requiredSkill, pinned, employeeIndex, nightBurden, holidayBurden);
    }

    private static PlanningProblem.AvailabilityData availability(
            long id, int employeeIndex, LocalDate date, PlanningProblem.AvailabilityKind kind) {
        return new PlanningProblem.AvailabilityData(id, employeeIndex, date, kind);
    }

    private static RosterSolution solution(PlanningProblem problem) {
        return new RosterSolution(problem.employeeCount(), problem.initialEmployeeIndexByShift(), ZERO);
    }
}
