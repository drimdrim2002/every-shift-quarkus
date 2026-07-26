package org.acme.solver;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;

import org.acme.model.Shift;

/**
 * Shift와 날짜 간의 실제시간/논리시간 매칭 정책을 제공합니다.
 */
public final class ShiftDateMatcher {

    private static final String SHIFT_TYPE_NIGHT = "N";
    private static final LocalTime NIGHT_LOGICAL_DAY_CUTOFF = LocalTime.of(6, 0);

    private ShiftDateMatcher() {
    }

    public static boolean matchesActualOrLogicalDate(Shift shift, LocalDate targetDate) {
        return matchesActualOrLogicalDate(
                shift.getStart(),
                shift.getEnd(),
                shift.getShiftCode(),
                targetDate);
    }

    /**
     * 모델 구현에 의존하지 않는 값 기반 날짜 매칭 정책입니다.
     */
    public static boolean matchesActualOrLogicalDate(
            LocalDateTime start,
            LocalDateTime end,
            String shiftCode,
            LocalDate targetDate) {
        return overlapsActualDate(start, end, targetDate)
                || targetDate.equals(resolveLogicalDate(start, shiftCode));
    }

    public static boolean overlapsActualDate(Shift shift, LocalDate targetDate) {
        return overlapsActualDate(shift.getStart(), shift.getEnd(), targetDate);
    }

    public static boolean overlapsActualDate(
            LocalDateTime start,
            LocalDateTime end,
            LocalDate targetDate) {
        LocalDateTime dayStart = targetDate.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        return start.isBefore(dayEnd) && end.isAfter(dayStart);
    }

    /**
     * 시프트의 논리일(logical date)을 계산합니다.
     *
     * 정책:
     * - Night(N) 시프트이면서 시작 시각이 06:00 이전이면, 실제 시작일의 전날을 논리일로 사용합니다.
     * - 그 외(Night 06:00 이후 시작, Day/Evening)는 실제 시작일을 논리일로 사용합니다.
     *
     * 예시:
     * - N 00:00~08:00 -> 논리일 = 시작일 - 1일
     * - N 23:00~07:00 -> 논리일 = 시작일
     */
    public static LocalDate resolveLogicalDate(Shift shift) {
        return resolveLogicalDate(shift.getStart(), shift.getShiftCode());
    }

    public static LocalDate resolveLogicalDate(LocalDateTime start, String shiftCode) {
        LocalDate actualStartDate = start.toLocalDate();
        if (!isNightShift(shiftCode)) {
            return actualStartDate;
        }
        LocalTime startTime = start.toLocalTime();
        return startTime.isBefore(NIGHT_LOGICAL_DAY_CUTOFF)
                ? actualStartDate.minusDays(1)
                : actualStartDate;
    }

    private static boolean isNightShift(String shiftCode) {
        if (shiftCode == null) {
            return false;
        }
        return SHIFT_TYPE_NIGHT.equals(shiftCode.trim().toUpperCase(Locale.ROOT));
    }
}
