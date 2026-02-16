package org.acme.solver;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

import org.acme.model.Shift;

/**
 * Shift와 날짜 간의 실제시간/논리시간 매칭 정책을 제공합니다.
 */
public final class ShiftDateMatcher {

    private static final String SHIFT_TYPE_NIGHT = "N";

    private ShiftDateMatcher() {
    }

    public static boolean matchesActualOrLogicalDate(Shift shift, LocalDate targetDate) {
        return overlapsActualDate(shift, targetDate) || targetDate.equals(resolveLogicalDate(shift));
    }

    public static boolean overlapsActualDate(Shift shift, LocalDate targetDate) {
        LocalDateTime dayStart = targetDate.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        return shift.getStart().isBefore(dayEnd) && shift.getEnd().isAfter(dayStart);
    }

    public static LocalDate resolveLogicalDate(Shift shift) {
        LocalDate actualStartDate = shift.getStart().toLocalDate();
        return isNightShift(shift) ? actualStartDate.minusDays(1) : actualStartDate;
    }

    private static boolean isNightShift(Shift shift) {
        if (shift.getShiftCode() == null) {
            return false;
        }
        return SHIFT_TYPE_NIGHT.equals(shift.getShiftCode().trim().toUpperCase(Locale.ROOT));
    }
}
