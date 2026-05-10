package org.acme.solver.validation;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.acme.model.Employee;
import org.acme.model.Shift;
import org.acme.solver.ShiftDateMatcher;
import org.slf4j.Logger;

/**
 * 야간 하드 제약 검증을 수행합니다.
 * - 4연속 Night 근무 금지
 * - 직원별 실제 시작월 기준 Night 근무 월 15회 이하
 */
public class NightHardConstraintValidator {

    private static final int MAX_MONTHLY_NIGHT_SHIFTS = 15;
    private static final String SHIFT_TYPE_NIGHT = "N";

    /**
     * 야간 관련 하드 제약을 검증합니다.
     *
     * @param shiftsByEmployee 직원별 시프트 맵
     * @param logger 사용할 로거
     * @throws ValidationException 검증 실패 시
     */
    public void validate(Map<Employee, List<Shift>> shiftsByEmployee, Logger logger) {
        for (Map.Entry<Employee, List<Shift>> entry : shiftsByEmployee.entrySet()) {
            Employee employee = entry.getKey();
            List<Shift> shifts = entry.getValue().stream()
                    .sorted(Comparator.comparing(Shift::getStart))
                    .collect(Collectors.toList());

            validateNoFourConsecutiveNightShifts(employee, shifts);
            validateMonthlyNightShiftLimit(employee, shifts);
        }
    }

    private void validateNoFourConsecutiveNightShifts(Employee employee, List<Shift> shifts) {
        List<Shift> nightShifts = shifts.stream()
                .filter(this::isNightShift)
                .sorted(Comparator.comparing(Shift::getStart))
                .collect(Collectors.toList());

        int consecutiveNightCount = 0;
        LocalDate previousLogicalDate = null;

        for (Shift nightShift : nightShifts) {
            LocalDate logicalDate = ShiftDateMatcher.resolveLogicalDate(nightShift);

            if (previousLogicalDate == null) {
                consecutiveNightCount = 1;
            } else if (logicalDate.equals(previousLogicalDate.plusDays(1))) {
                consecutiveNightCount++;
            } else if (!logicalDate.equals(previousLogicalDate)) {
                consecutiveNightCount = 1;
            }

            if (consecutiveNightCount >= 4) {
                throw new ValidationException(
                        "Employee '%s' violates no-four-consecutive-night-shifts: logicalDate=%s, shiftId=%d",
                        employee.getName(), logicalDate, nightShift.getId());
            }

            previousLogicalDate = logicalDate;
        }
    }

    private void validateMonthlyNightShiftLimit(Employee employee, List<Shift> shifts) {
        Map<YearMonth, Long> nightShiftCountByMonth = shifts.stream()
                .filter(this::isNightShift)
                .collect(Collectors.groupingBy(shift -> YearMonth.from(shift.getStart()), Collectors.counting()));

        for (Map.Entry<YearMonth, Long> entry : nightShiftCountByMonth.entrySet()) {
            if (entry.getValue() <= MAX_MONTHLY_NIGHT_SHIFTS) {
                continue;
            }

            throw new ValidationException(
                    "Employee '%s' violates monthly-night-shift-limit: month=%s, nightShiftCount=%d",
                    employee.getName(), entry.getKey(), entry.getValue());
        }
    }

    private boolean isNightShift(Shift shift) {
        return SHIFT_TYPE_NIGHT.equals(normalizeShiftCode(shift));
    }

    private String normalizeShiftCode(Shift shift) {
        if (shift.getShiftCode() == null) {
            return "";
        }
        return shift.getShiftCode().trim().toUpperCase(Locale.ROOT);
    }
}
