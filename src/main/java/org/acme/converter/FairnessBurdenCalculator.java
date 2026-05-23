package org.acme.converter;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.acme.api.dto.PlanningRequest;
import org.acme.model.ScheduleState;
import org.acme.model.Shift;
import org.acme.solver.ShiftDateMatcher;

/**
 * 변환 단계에서 시프트별 연간 공정성 부담 점수를 계산합니다.
 */
public class FairnessBurdenCalculator {

    public void apply(
            Iterable<Shift> shifts,
            ScheduleState scheduleState,
            Iterable<PlanningRequest.PublicHolidayInfo> publicHolidays) {
        if (shifts == null) {
            return;
        }

        Set<LocalDate> publicHolidayDates = toPublicHolidayDates(publicHolidays);
        for (Shift shift : shifts) {
            if (shift != null) {
                shift.setFairnessBurdenScore(calculate(shift, scheduleState, publicHolidayDates));
            }
        }
    }

    int calculate(Shift shift, ScheduleState scheduleState, Set<LocalDate> publicHolidayDates) {
        if (shift == null || scheduleState == null || shift.getStart() == null) {
            return 0;
        }
        LocalDate startInclusive = scheduleState.getFirstDraftDate();
        Integer draftLength = scheduleState.getDraftLength();
        if (startInclusive == null || draftLength == null) {
            return 0;
        }

        String shiftCode = normalizeShiftCode(shift.getShiftCode());
        LocalDate burdenDate = burdenDate(shift, shiftCode);
        if (burdenDate == null) {
            return 0;
        }

        LocalDate endExclusive = startInclusive.plusDays(draftLength);
        if (burdenDate.isBefore(startInclusive) || !burdenDate.isBefore(endExclusive)) {
            return 0;
        }

        int burden = "N".equals(shiftCode) ? 1 : 0;
        if (hasHolidayComponent(burdenDate, shiftCode, publicHolidayDates)) {
            burden++;
        }
        return burden;
    }

    private Set<LocalDate> toPublicHolidayDates(Iterable<PlanningRequest.PublicHolidayInfo> publicHolidays) {
        Set<LocalDate> dates = new HashSet<>();
        if (publicHolidays == null) {
            return dates;
        }
        for (PlanningRequest.PublicHolidayInfo publicHoliday : publicHolidays) {
            if (publicHoliday != null && publicHoliday.date() != null) {
                dates.add(publicHoliday.date());
            }
        }
        return dates;
    }

    private LocalDate burdenDate(Shift shift, String shiftCode) {
        if ("N".equals(shiftCode)) {
            return ShiftDateMatcher.resolveLogicalDate(shift);
        }
        if ("D".equals(shiftCode) || "E".equals(shiftCode)) {
            return shift.getStart().toLocalDate();
        }
        return null;
    }

    private boolean hasHolidayComponent(LocalDate date, String shiftCode, Set<LocalDate> publicHolidayDates) {
        if ("N".equals(shiftCode)) {
            LocalDate nextDay = date.plusDays(1);
            boolean isNextDayHoliday = publicHolidayDates != null && publicHolidayDates.contains(nextDay);
            boolean isNextDayWeekend = nextDay.getDayOfWeek() == DayOfWeek.SATURDAY || nextDay.getDayOfWeek() == DayOfWeek.SUNDAY;
            return isNextDayHoliday || isNextDayWeekend;
        }
        if ("D".equals(shiftCode) || "E".equals(shiftCode)) {
            boolean isCurrentDayHoliday = publicHolidayDates != null && publicHolidayDates.contains(date);
            boolean isCurrentDayWeekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
            return isCurrentDayHoliday || isCurrentDayWeekend;
        }
        return false;
    }

    private String normalizeShiftCode(String shiftCode) {
        if (shiftCode == null) {
            return null;
        }
        return shiftCode.trim().toUpperCase(Locale.ROOT);
    }
}
