package org.acme.solver.algorithm;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.acme.model.Availability;
import org.acme.model.AvailabilityType;
import org.acme.model.Employee;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.score.buildin.bendable.BendableScore;
import org.optaplanner.test.api.score.stream.ConstraintVerifier;

class EmployeeSchedulingConstraintProviderTest {

    ConstraintVerifier<EmployeeSchedulingConstraintProvider, EmployeeSchedule> constraintVerifier = ConstraintVerifier
            .build(
                    new EmployeeSchedulingConstraintProvider(), EmployeeSchedule.class, Shift.class);

    @Test
    void oneShiftPerDay() {
        Employee employee = new Employee();
        employee.setId("E1");
        employee.setName("Test Employee");

        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift shift1 = new Shift();
        shift1.setId(1L);
        shift1.setEmployee(employee);
        shift1.setStart(date.atTime(9, 0));
        shift1.setEnd(date.atTime(17, 0));

        Shift shift2 = new Shift();
        shift2.setId(2L);
        shift2.setEmployee(employee);
        shift2.setStart(date.atTime(18, 0));
        shift2.setEnd(date.atTime(22, 0));

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::oneShiftPerDay)
                .given(shift1, shift2)
                .penalizesBy(1);
    }

    @Test
    void atLeast12HoursBetweenTwoShifts() {
        Employee employee = new Employee();
        employee.setId("E1");
        employee.setName("Test Employee");

        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift shift1 = new Shift();
        shift1.setId(1L);
        shift1.setEmployee(employee);
        shift1.setStart(date.atTime(9, 0));
        shift1.setEnd(date.atTime(17, 0)); // Ends 17:00

        Shift shift2 = new Shift();
        shift2.setId(2L);
        shift2.setEmployee(employee);
        shift2.setStart(date.plusDays(1).atTime(4, 0)); // Starts 04:00 next day (11 hours gap)
        shift2.setEnd(date.plusDays(1).atTime(12, 0));

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::atLeast12HoursBetweenTwoShifts)
                .given(shift1, shift2)
                .penalizesBy(60); // 12h(720m) - 11h(660m) = 60m penalty
    }

    // 경계값 테스트 (Edge Case Tests)

    @Test
    void atLeast12HoursBetweenTwoShifts_EdgeCase_11h59m() {
        // 17:00 종료 → 다음 날 04:59 시작 (719분 휴식)
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift shift1 = createShift(1L, employee, date.atTime(17, 0), date.atTime(17, 0), "D");
        Shift shift2 = createShift(2L, employee, date.plusDays(1).atTime(4, 59), date.plusDays(1).atTime(12, 59), "D");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::atLeast12HoursBetweenTwoShifts)
                .given(shift1, shift2)
                .penalizesBy(1); // 720 - 719 = 1분 페널티
    }

    @Test
    void atLeast12HoursBetweenTwoShifts_Exact12h() {
        // 17:00 종료 → 다음 날 05:00 시작 (720분 휴식)
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift shift1 = createShift(1L, employee, date.atTime(17, 0), date.atTime(17, 0), "D");
        Shift shift2 = createShift(2L, employee, date.plusDays(1).atTime(5, 0), date.plusDays(1).atTime(13, 0), "D");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::atLeast12HoursBetweenTwoShifts)
                .given(shift1, shift2)
                .penalizesBy(0); // 정확히 12시간 → 페널티 없음
    }

    @Test
    void atLeast12HoursBetweenTwoShifts_12h01m() {
        // 17:00 종료 → 다음 날 05:01 시작 (721분 휴식)
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift shift1 = createShift(1L, employee, date.atTime(17, 0), date.atTime(17, 0), "D");
        Shift shift2 = createShift(2L, employee, date.plusDays(1).atTime(5, 1), date.plusDays(1).atTime(13, 1), "D");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::atLeast12HoursBetweenTwoShifts)
                .given(shift1, shift2)
                .penalizesBy(0); // 12시간 1분 → 페널티 없음
    }

    // Shift 타입 조합 테스트 (Shift Type Combination Tests)

    @Test
    void atLeast12HoursBetweenTwoShifts_NightToDaySameDay() {
        // Night (00:00-08:00) → Day (08:00-16:00) 같은 날
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift nightShift = createShift(1L, employee, date.atTime(0, 0), date.atTime(8, 0), "N");
        Shift dayShift = createShift(2L, employee, date.atTime(8, 0), date.atTime(16, 0), "D");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::atLeast12HoursBetweenTwoShifts)
                .given(nightShift, dayShift)
                .penalizesBy(720); // 0분 휴식 → 720분 페널티
    }

    @Test
    void atLeast12HoursBetweenTwoShifts_DayToEveningSameDay() {
        // Day (08:00-16:00) → Evening (16:00-00:00+1) 같은 날
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift dayShift = createShift(1L, employee, date.atTime(8, 0), date.atTime(16, 0), "D");
        Shift eveningShift = createShift(2L, employee, date.atTime(16, 0), date.plusDays(1).atTime(0, 0), "E");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::atLeast12HoursBetweenTwoShifts)
                .given(dayShift, eveningShift)
                .penalizesBy(720); // 0분 휴식 → 720분 페널티
    }

    @Test
    void atLeast12HoursBetweenTwoShifts_EveningToNextDayDay() {
        // Evening (16:00-00:00+1) → 다음 날 Day (08:00-16:00)
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift eveningShift = createShift(1L, employee, date.atTime(16, 0), date.plusDays(1).atTime(0, 0), "E");
        Shift nextDayShift = createShift(2L, employee, date.plusDays(1).atTime(8, 0), date.plusDays(1).atTime(16, 0), "D");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::atLeast12HoursBetweenTwoShifts)
                .given(eveningShift, nextDayShift)
                .penalizesBy(240); // 480분(8시간) 휴식 → 720 - 480 = 240분 페널티
    }

    @Test
    void atLeast12HoursBetweenTwoShifts_DayToNextDayNight() {
        // Day (08:00-16:00) → 다음 날 Night (00:00-08:00)
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift dayShift = createShift(1L, employee, date.atTime(8, 0), date.atTime(16, 0), "D");
        Shift nextNightShift = createShift(2L, employee, date.plusDays(1).atTime(0, 0), date.plusDays(1).atTime(8, 0), "N");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::atLeast12HoursBetweenTwoShifts)
                .given(dayShift, nextNightShift)
                .penalizesBy(240); // 480분(8시간) 휴식 → 720 - 480 = 240분 페널티
    }

    @Test
    void atLeast12HoursBetweenTwoShifts_WhenIdOrderDiffersFromTimeOrder() {
        // ID 순서와 시간 순서가 달라도 실제 휴식시간(8시간)을 기준으로 페널티가 계산되어야 함
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift laterShiftWithLowerId = createShift(1L, employee, date.plusDays(1).atTime(8, 0),
                date.plusDays(1).atTime(16, 0), "D");
        Shift earlierShiftWithHigherId = createShift(2L, employee, date.atTime(16, 0), date.plusDays(1).atTime(0, 0),
                "E");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::atLeast12HoursBetweenTwoShifts)
                .given(laterShiftWithLowerId, earlierShiftWithHigherId)
                .penalizesBy(240); // 8시간(480분) 휴식 → 720 - 480 = 240분 페널티
    }

    @Test
    void noThreeConsecutiveNightShifts_PenalizesThreeConsecutiveLogicalDays() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift night1 = createShift(1L, employee, date.plusDays(1).atTime(0, 0), date.plusDays(1).atTime(8, 0), "N");
        Shift night2 = createShift(2L, employee, date.plusDays(2).atTime(0, 0), date.plusDays(2).atTime(8, 0), "N");
        Shift night3 = createShift(3L, employee, date.plusDays(3).atTime(0, 0), date.plusDays(3).atTime(8, 0), "N");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::noThreeConsecutiveNightShifts)
                .given(night1, night2, night3)
                .penalizesBy(1);
    }

    @Test
    void noThreeConsecutiveNightShifts_OnlyTwoNights() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift night1 = createShift(1L, employee, date.plusDays(1).atTime(0, 0), date.plusDays(1).atTime(8, 0), "N");
        Shift night2 = createShift(2L, employee, date.plusDays(2).atTime(0, 0), date.plusDays(2).atTime(8, 0), "N");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::noThreeConsecutiveNightShifts)
                .given(night1, night2)
                .penalizesBy(0);
    }

    @Test
    void noThreeConsecutiveNightShifts_FourConsecutiveLogicalDays() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift night1 = createShift(1L, employee, date.plusDays(1).atTime(0, 0), date.plusDays(1).atTime(8, 0), "N");
        Shift night2 = createShift(2L, employee, date.plusDays(2).atTime(0, 0), date.plusDays(2).atTime(8, 0), "N");
        Shift night3 = createShift(3L, employee, date.plusDays(3).atTime(0, 0), date.plusDays(3).atTime(8, 0), "N");
        Shift night4 = createShift(4L, employee, date.plusDays(4).atTime(0, 0), date.plusDays(4).atTime(8, 0), "N");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::noThreeConsecutiveNightShifts)
                .given(night1, night2, night3, night4)
                .penalizesBy(2);
    }

    @Test
    void noThreeConsecutiveNightShifts_GapBreaksConsecutive() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift night1 = createShift(1L, employee, date.plusDays(1).atTime(0, 0), date.plusDays(1).atTime(8, 0), "N");
        Shift night3 = createShift(2L, employee, date.plusDays(3).atTime(0, 0), date.plusDays(3).atTime(8, 0), "N");
        Shift night4 = createShift(3L, employee, date.plusDays(4).atTime(0, 0), date.plusDays(4).atTime(8, 0), "N");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::noThreeConsecutiveNightShifts)
                .given(night1, night3, night4)
                .penalizesBy(0);
    }

    @Test
    void noThreeConsecutiveNightShifts_IncludesPinnedShifts() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift pinnedNight1 = createShift(1L, employee, date.plusDays(1).atTime(0, 0), date.plusDays(1).atTime(8, 0),
                "N");
        pinnedNight1.setPinned(true);
        Shift night2 = createShift(2L, employee, date.plusDays(2).atTime(0, 0), date.plusDays(2).atTime(8, 0), "N");
        Shift night3 = createShift(3L, employee, date.plusDays(3).atTime(0, 0), date.plusDays(3).atTime(8, 0), "N");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::noThreeConsecutiveNightShifts)
                .given(pinnedNight1, night2, night3)
                .penalizesBy(1);
    }

    @Test
    void atLeast32HoursFromNightToNextDayShift_EdgeCase31h59m() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift nightShift = createShift(1L, employee, date.plusDays(1).atTime(0, 0), date.plusDays(1).atTime(8, 0), "N");
        Shift nextDayShift = createShift(2L, employee, date.plusDays(2).atTime(15, 59), date.plusDays(2).atTime(23, 59),
                "D");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::atLeast32HoursFromNightToNextDayShift)
                .given(nightShift, nextDayShift)
                .penalizesBy(1);
    }

    @Test
    void atLeast32HoursFromNightToNextDayShift_Exact32Hours() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift nightShift = createShift(1L, employee, date.plusDays(1).atTime(0, 0), date.plusDays(1).atTime(8, 0), "N");
        Shift nextDayShift = createShift(2L, employee, date.plusDays(2).atTime(16, 0), date.plusDays(3).atTime(0, 0), "D");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::atLeast32HoursFromNightToNextDayShift)
                .given(nightShift, nextDayShift)
                .penalizesBy(0);
    }

    @Test
    void atLeast32HoursFromNightToNextDayShift_UsesNextDayOnly() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift nightShift = createShift(1L, employee, date.plusDays(1).atTime(0, 0), date.plusDays(1).atTime(8, 0), "N");
        Shift nearDayShift = createShift(2L, employee, date.plusDays(2).atTime(0, 0), date.plusDays(2).atTime(8, 0), "D");
        Shift farDayShift = createShift(3L, employee, date.plusDays(3).atTime(16, 0), date.plusDays(4).atTime(0, 0), "D");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::atLeast32HoursFromNightToNextDayShift)
                .given(nightShift, nearDayShift, farDayShift)
                .penalizesBy(960);
    }

    @Test
    void atLeast32HoursFromNightToNextDayShift_NoFollowingDayShift() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift dayBeforeNight = createShift(1L, employee, date.plusDays(1).atTime(8, 0), date.plusDays(1).atTime(16, 0), "D");
        Shift nightShift = createShift(2L, employee, date.plusDays(2).atTime(0, 0), date.plusDays(2).atTime(8, 0), "N");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::atLeast32HoursFromNightToNextDayShift)
                .given(dayBeforeNight, nightShift)
                .penalizesBy(0);
    }

    @Test
    void atLeast32HoursFromNightToNextDayShift_IncludesPinnedShifts() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift pinnedNight = createShift(1L, employee, date.plusDays(1).atTime(0, 0), date.plusDays(1).atTime(8, 0), "N");
        pinnedNight.setPinned(true);
        Shift nextDayShift = createShift(2L, employee, date.plusDays(2).atTime(15, 59), date.plusDays(2).atTime(23, 59),
                "D");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::atLeast32HoursFromNightToNextDayShift)
                .given(pinnedNight, nextDayShift)
                .penalizesBy(1);
    }

    @Test
    void twoConsecutiveNightShiftsRequire48HoursBeforeNextShift_EdgeCase47h59m() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift night1 = createShift(1L, employee, date.plusDays(1).atTime(0, 0), date.plusDays(1).atTime(8, 0), "N");
        Shift night2 = createShift(2L, employee, date.plusDays(2).atTime(0, 0), date.plusDays(2).atTime(8, 0), "N");
        Shift nextDayShift = createShift(3L, employee, date.plusDays(4).atTime(7, 59), date.plusDays(4).atTime(15, 59),
                "D");

        constraintVerifier.verifyThat()
                .given(night1, night2, nextDayShift)
                .scores(BendableScore.of(new int[] { -1 }, new int[] { 0, -41, 0 }));
    }

    @Test
    void twoConsecutiveNightShiftsRequire48HoursBeforeNextShift_Exact48Hours() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift night1 = createShift(1L, employee, date.plusDays(1).atTime(0, 0), date.plusDays(1).atTime(8, 0), "N");
        Shift night2 = createShift(2L, employee, date.plusDays(2).atTime(0, 0), date.plusDays(2).atTime(8, 0), "N");
        Shift nextDayShift = createShift(3L, employee, date.plusDays(4).atTime(8, 0), date.plusDays(4).atTime(16, 0),
                "D");

        constraintVerifier.verifyThat()
                .given(night1, night2, nextDayShift)
                .scores(BendableScore.of(new int[] { 0 }, new int[] { 0, -41, 0 }));
    }

    @Test
    void twoConsecutiveNightShiftsRequire48HoursBeforeNextShift_AppliesToEveningShift() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift night1 = createShift(1L, employee, date.plusDays(1).atTime(0, 0), date.plusDays(1).atTime(8, 0), "N");
        Shift night2 = createShift(2L, employee, date.plusDays(2).atTime(0, 0), date.plusDays(2).atTime(8, 0), "N");
        Shift nextEveningShift = createShift(3L, employee, date.plusDays(4).atTime(7, 59), date.plusDays(4).atTime(15, 59),
                "E");

        constraintVerifier.verifyThat()
                .given(night1, night2, nextEveningShift)
                .scores(BendableScore.of(new int[] { -1 }, new int[] { 0, -45, 0 }));
    }

    @Test
    void twoConsecutiveNightShiftsRequire48HoursBeforeNextShift_AppliesToNightShift() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift night1 = createShift(1L, employee, date.plusDays(1).atTime(0, 0), date.plusDays(1).atTime(8, 0), "N");
        Shift night2 = createShift(2L, employee, date.plusDays(2).atTime(0, 0), date.plusDays(2).atTime(8, 0), "N");
        Shift nextNightShift = createShift(3L, employee, date.plusDays(4).atTime(7, 59), date.plusDays(4).atTime(15, 59),
                "N");

        constraintVerifier.verifyThat()
                .given(night1, night2, nextNightShift)
                .scores(BendableScore.of(new int[] { -1 }, new int[] { 0, -90, 0 }));
    }

    @Test
    void twoConsecutiveNightShiftsRequire48HoursBeforeNextShift_LogicalDateGapDoesNotApply() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift night1 = createShift(1L, employee, date.plusDays(1).atTime(0, 0), date.plusDays(1).atTime(8, 0), "N");
        Shift night3 = createShift(2L, employee, date.plusDays(3).atTime(0, 0), date.plusDays(3).atTime(8, 0), "N");
        Shift nextDayShift = createShift(3L, employee, date.plusDays(5).atTime(7, 59), date.plusDays(5).atTime(15, 59),
                "D");

        constraintVerifier.verifyThat()
                .given(night1, night3, nextDayShift)
                .scores(BendableScore.of(new int[] { 0 }, new int[] { 0, -41, 0 }));
    }

    @Test
    void monthlyNightShifts_ActualStartMonthPenalizes16thNight() {
        Employee employee = createEmployee("E1");
        Shift[] marchNightShifts = createEveryOtherDayNightShifts(employee, LocalDate.of(2026, 3, 1), 16);

        constraintVerifier.verifyThat()
                .given((Object[]) marchNightShifts)
                .scores(BendableScore.of(new int[] { -1 }, new int[] { 0, -2560, 0 }));
    }

    @Test
    void monthlyNightShifts_ActualStartMonthAllows15Nights() {
        Employee employee = createEmployee("E1");
        Shift[] marchNightShifts = createEveryOtherDayNightShifts(employee, LocalDate.of(2026, 3, 1), 15);

        constraintVerifier.verifyThat()
                .given((Object[]) marchNightShifts)
                .scores(BendableScore.of(new int[] { 0 }, new int[] { 0, -2250, 0 }));
    }

    @Test
    void monthlyNightShifts_UsesActualStartMonthWhenLogicalDateIsPreviousMonth() {
        Employee employee = createEmployee("E1");
        Shift[] marchNightShifts = createEveryOtherDayNightShifts(employee, LocalDate.of(2026, 3, 1), 16);

        constraintVerifier.verifyThat()
                .given((Object[]) marchNightShifts)
                .scores(BendableScore.of(new int[] { -1 }, new int[] { 0, -2560, 0 }));
    }

    @Test
    void monthlyNightShifts_CountsEachEmployeeAndMonthIndependently() {
        Employee employee1 = createEmployee("E1");
        Employee employee2 = createEmployee("E2");
        Shift[] employee1MarchNightShifts = createEveryOtherDayNightShifts(employee1, LocalDate.of(2026, 3, 1), 15,
                1L);
        Shift[] employee1AprilNightShifts = createEveryOtherDayNightShifts(employee1, LocalDate.of(2026, 4, 1), 1,
                101L);
        Shift[] employee2MarchNightShifts = createEveryOtherDayNightShifts(employee2, LocalDate.of(2026, 3, 1), 15,
                201L);

        constraintVerifier.verifyThat()
                .given(combine(employee1MarchNightShifts, employee1AprilNightShifts, employee2MarchNightShifts))
                .scores(BendableScore.of(new int[] { 0 }, new int[] { 0, -4810, 0 }));
    }

    @Test

    void softScoreLevels_UndesiredAndFair() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);
        Shift shift = createShift(1L, employee, date, 9, 17);
        Availability availability = new Availability(employee, date, AvailabilityType.UNDESIRED);

        constraintVerifier.verifyThat()
                .given(shift, availability)
                .scores(BendableScore.of(new int[] { 0 }, new int[] { -480, -1, 0 }));
    }

    @Test
    void undesiredDayForEmployee_MatchesNightLogicalDate() {
        Employee employee = createEmployee("E1");
        LocalDate logicalDate = LocalDate.of(2025, 1, 5);
        Shift nightShift = createShift(1L, employee, logicalDate.plusDays(1).atTime(0, 0), logicalDate.plusDays(1).atTime(8, 0),
                "N");
        Availability availability = new Availability(employee, logicalDate, AvailabilityType.UNDESIRED);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::undesiredDayForEmployee)
                .given(nightShift, availability)
                .penalizesBy(480);
    }

    @Test
    void undesiredDayForEmployee_MatchesNightActualDate() {
        Employee employee = createEmployee("E1");
        LocalDate logicalDate = LocalDate.of(2025, 1, 5);
        LocalDate actualDate = logicalDate.plusDays(1);
        Shift nightShift = createShift(1L, employee, actualDate.atTime(0, 0), actualDate.atTime(8, 0), "N");
        Availability availability = new Availability(employee, actualDate, AvailabilityType.UNDESIRED);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::undesiredDayForEmployee)
                .given(nightShift, availability)
                .penalizesBy(480);
    }

    @Test
    void undesiredDayForEmployee_NightBeforeCutoff_UsesPreviousLogicalDate() {
        Employee employee = createEmployee("E1");
        LocalDate logicalDate = LocalDate.of(2025, 1, 5);
        Shift nightShift = createShift(1L, employee, logicalDate.plusDays(1).atTime(5, 59), logicalDate.plusDays(1).atTime(13, 59),
                "N");
        Availability availability = new Availability(employee, logicalDate, AvailabilityType.UNDESIRED);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::undesiredDayForEmployee)
                .given(nightShift, availability)
                .penalizesBy(480);
    }

    @Test
    void undesiredDayForEmployee_NightAtCutoff_DoesNotUsePreviousLogicalDate() {
        Employee employee = createEmployee("E1");
        LocalDate previousDate = LocalDate.of(2025, 1, 5);
        Shift nightShift = createShift(1L, employee, previousDate.plusDays(1).atTime(6, 0), previousDate.plusDays(1).atTime(14, 0),
                "N");
        Availability availability = new Availability(employee, previousDate, AvailabilityType.UNDESIRED);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::undesiredDayForEmployee)
                .given(nightShift, availability)
                .penalizesBy(0);
    }

    @Test
    void undesiredDayForEmployee_AppliesLogicalAndActualDatesOncePerShift() {
        Employee employee = createEmployee("E1");
        LocalDate logicalDate = LocalDate.of(2025, 1, 5);
        LocalDate actualDate = logicalDate.plusDays(1);
        Shift nightShift = createShift(1L, employee, actualDate.atTime(0, 0), actualDate.atTime(8, 0), "N");
        Availability logicalAvailability = new Availability(employee, logicalDate, AvailabilityType.UNDESIRED);
        Availability actualAvailability = new Availability(employee, actualDate, AvailabilityType.UNDESIRED);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::undesiredDayForEmployee)
                .given(nightShift, logicalAvailability, actualAvailability)
                .penalizesBy(480);
    }

    @Test
    void undesiredDayForEmployee_DeduplicatesSameDateMatchesPerShift() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 5);
        Shift shift = createShift(1L, employee, date, 9, 17);
        Availability firstAvailability = new Availability(employee, date, AvailabilityType.UNDESIRED);
        Availability duplicateAvailability = new Availability(employee, date, AvailabilityType.UNDESIRED);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::undesiredDayForEmployee)
                .given(shift, firstAvailability, duplicateAvailability)
                .penalizesBy(480);
    }

    @Test
    void undesiredDayForEmployee_IgnoresPinnedShift() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 5);
        Shift pinnedShift = createShift(1L, employee, date, 9, 17);
        pinnedShift.setPinned(true);
        Availability availability = new Availability(employee, date, AvailabilityType.UNDESIRED);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::undesiredDayForEmployee)
                .given(pinnedShift, availability)
                .penalizesBy(0);
    }

    @Test
    void softScoreLevels_FairOnly() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);
        Shift shift = createShift(1L, employee, date, 9, 17);

        constraintVerifier.verifyThat()
                .given(shift)
                .scores(BendableScore.of(new int[] { 0 }, new int[] { 0, -1, 0 }));
    }

    @Test
    void fairShiftDistribution_EveningWeight() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);
        Shift eveningShift = createShift(1L, employee, date.atTime(16, 0), date.plusDays(1).atTime(0, 0), "E");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::fairShiftDistribution)
                .given(eveningShift)
                .penalizesBy(5);
    }

    @Test
    void fairShiftDistribution_NightWeight() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);
        Shift nightShift = createShift(1L, employee, date.atTime(0, 0), date.atTime(8, 0), "N");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::fairShiftDistribution)
                .given(nightShift)
                .penalizesBy(10);
    }

    @Test
    void fairShiftDistribution_NightQuadraticPenalty() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);
        Shift nightShift1 = createShift(1L, employee, date.atTime(0, 0), date.atTime(8, 0), "N");
        Shift nightShift2 = createShift(2L, employee, date.plusDays(1).atTime(0, 0), date.plusDays(1).atTime(8, 0),
                "N");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::fairShiftDistribution)
                .given(nightShift1, nightShift2)
                .penalizesBy(40);
    }

    @Test
    void fairShiftDistribution_UnknownCodeFallbackWeight() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);
        Shift unknownShift = createShift(1L, employee, date.atTime(9, 0), date.atTime(17, 0), "X");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::fairShiftDistribution)
                .given(unknownShift)
                .penalizesBy(1);
    }

    @Test
    void softScoreLevels_DesiredAndFair() {
        Employee employee = createEmployee("E1");
        LocalDate date = LocalDate.of(2025, 1, 1);
        Shift shift = createShift(1L, employee, date, 9, 17);
        Availability availability = new Availability(employee, date, AvailabilityType.DESIRED);

        constraintVerifier.verifyThat()
                .given(shift, availability)
                .scores(BendableScore.of(new int[] { 0 }, new int[] { 0, -1, 480 }));
    }

    private Employee createEmployee(String id) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setName("Test Employee " + id);
        employee.setSkillSet(Set.of("ALL"));
        return employee;
    }

    private Shift createShift(Long id, Employee employee, LocalDate date, int startHour, int endHour) {
        return createShift(id, employee, date, startHour, endHour, "D");
    }

    private Shift createShift(Long id, Employee employee, LocalDate date, int startHour, int endHour, String shiftCode) {
        Shift shift = new Shift();
        shift.setId(id);
        shift.setEmployee(employee);
        shift.setStart(date.atTime(startHour, 0));
        shift.setEnd(date.atTime(endHour, 0));
        shift.setShiftCode(shiftCode);
        shift.setRequiredSkill("ALL");
        return shift;
    }

    private Shift createShift(Long id, Employee employee, LocalDateTime start, LocalDateTime end) {
        return createShift(id, employee, start, end, "D");
    }

    private Shift createShift(Long id, Employee employee, LocalDateTime start, LocalDateTime end, String shiftCode) {
        Shift shift = new Shift();
        shift.setId(id);
        shift.setEmployee(employee);
        shift.setStart(start);
        shift.setEnd(end);
        shift.setShiftCode(shiftCode);
        shift.setRequiredSkill("ALL");
        return shift;
    }

    private Shift[] createEveryOtherDayNightShifts(Employee employee, LocalDate firstStartDate, int count) {
        return createEveryOtherDayNightShifts(employee, firstStartDate, count, 1L);
    }

    private Shift[] createEveryOtherDayNightShifts(Employee employee, LocalDate firstStartDate, int count, long firstId) {
        Shift[] shifts = new Shift[count];
        for (int index = 0; index < count; index++) {
            LocalDate startDate = firstStartDate.plusDays(index * 2L);
            shifts[index] = createShift(firstId + index, employee, startDate.atTime(0, 0), startDate.atTime(8, 0), "N");
        }
        return shifts;
    }

    private Object[] combine(Shift[]... shiftGroups) {
        List<Shift> shifts = new ArrayList<>();
        for (Shift[] shiftGroup : shiftGroups) {
            shifts.addAll(List.of(shiftGroup));
        }
        return shifts.toArray();
    }
}
