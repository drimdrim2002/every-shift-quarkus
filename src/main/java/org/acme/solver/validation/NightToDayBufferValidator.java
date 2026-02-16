package org.acme.solver.validation;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.acme.model.Employee;
import org.acme.model.Shift;
import org.slf4j.Logger;

/**
 * Night -> Day 버퍼 검증을 수행합니다.
 * - 논리 근무일 기준으로 Night 이후 1~2일 내 Day 배정을 금지합니다.
 * - Draft 범위만 대상으로 하기 위해 pinned 시프트는 제외합니다.
 */
public class NightToDayBufferValidator {

    private static final long MIN_NIGHT_TO_DAY_GAP_DAYS = 1L;
    private static final long MAX_NIGHT_TO_DAY_GAP_DAYS = 2L;

    /**
     * Night -> Day 버퍼 제약을 검증합니다.
     *
     * @param shiftsByEmployee 직원별 시프트 맵
     * @param logger 사용할 로거
     * @throws ValidationException 검증 실패 시
     */
    public void validate(Map<Employee, List<Shift>> shiftsByEmployee, Logger logger) {
        for (Map.Entry<Employee, List<Shift>> entry : shiftsByEmployee.entrySet()) {
            Employee employee = entry.getKey();
            List<Shift> draftShifts = entry.getValue().stream()
                    .filter(shift -> !shift.isPinned())
                    .collect(Collectors.toList());

            List<Shift> nightShifts = draftShifts.stream()
                    .filter(this::isNightShift)
                    .collect(Collectors.toList());

            List<Shift> dayShifts = draftShifts.stream()
                    .filter(this::isDayShift)
                    .collect(Collectors.toList());

            for (Shift nightShift : nightShifts) {
                LocalDate nightLogicalDate = nightShift.getStart().toLocalDate().minusDays(1);

                for (Shift dayShift : dayShifts) {
                    long dayGap = ChronoUnit.DAYS.between(nightLogicalDate, dayShift.getStart().toLocalDate());
                    if (dayGap >= MIN_NIGHT_TO_DAY_GAP_DAYS && dayGap <= MAX_NIGHT_TO_DAY_GAP_DAYS) {
                        throw new ValidationException(
                                "Employee '%s' violates Night->Day buffer: nightLogicalDate=%s, dayDate=%s, gapDays=%d",
                                employee.getName(), nightLogicalDate, dayShift.getStart().toLocalDate(), dayGap);
                    }
                }
            }
        }
    }

    private boolean isNightShift(Shift shift) {
        return "N".equals(normalizeShiftCode(shift));
    }

    private boolean isDayShift(Shift shift) {
        return "D".equals(normalizeShiftCode(shift));
    }

    private String normalizeShiftCode(Shift shift) {
        if (shift.getShiftCode() == null) {
            return "";
        }
        return shift.getShiftCode().trim().toUpperCase(Locale.ROOT);
    }
}

