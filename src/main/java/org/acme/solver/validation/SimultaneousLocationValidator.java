package org.acme.solver.validation;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.acme.converter.ShiftOverlapCalculator;
import org.acme.model.Employee;
import org.acme.model.Shift;
import org.slf4j.Logger;

/**
 * 동시 다중 위치 근무 검증을 수행합니다.
 * - 직원이 동시에 서로 다른 위치에서 근무하지 않는지 확인
 */
public class SimultaneousLocationValidator {

    /**
     * 동시 다중 위치 근무를 검증합니다.
     *
     * @param shiftsByEmployee 직원별 시프트 맵
     * @param logger 사용할 로거
     * @throws ValidationException 검증 실패 시
     */
    public void validate(Map<Employee, List<Shift>> shiftsByEmployee, Logger logger) {
        for (Map.Entry<Employee, List<Shift>> entry : shiftsByEmployee.entrySet()) {
            Employee employee = entry.getKey();
            List<Shift> shifts = entry.getValue();

            // 날짜별로 그룹화
            Map<LocalDate, List<Shift>> shiftsByDate = shifts.stream()
                    .collect(java.util.stream.Collectors.groupingBy(s -> s.getStart().toLocalDate()));

            for (Map.Entry<LocalDate, List<Shift>> dateEntry : shiftsByDate.entrySet()) {
                LocalDate date = dateEntry.getKey();
                List<Shift> dailyShifts = dateEntry.getValue();

                // 해당 날짜의 모든 Shift 쌍 비교
                for (int i = 0; i < dailyShifts.size(); i++) {
                    for (int j = i + 1; j < dailyShifts.size(); j++) {
                        Shift shift1 = dailyShifts.get(i);
                        Shift shift2 = dailyShifts.get(j);

                        // 서로 다른 location이고 시간이 중복되는지 확인
                        if (!shift1.getLocation().equals(shift2.getLocation())) {
                            int overlapMinutes = ShiftOverlapCalculator.getMinuteOverlap(shift1, shift2);
                            if (overlapMinutes > 0) {
                                throw new ValidationException(
                                        "Employee '%s' is working simultaneously at different locations on %s: " +
                                                "'%s' (%s-%s) and '%s' (%s-%s) with %d minutes overlap",
                                        employee.getName(), date,
                                        shift1.getLocation(), shift1.getStart().toLocalTime(),
                                        shift1.getEnd().toLocalTime(),
                                        shift2.getLocation(), shift2.getStart().toLocalTime(),
                                        shift2.getEnd().toLocalTime(),
                                        overlapMinutes);
                            }
                        }
                    }
                }
            }
        }
    }
}
