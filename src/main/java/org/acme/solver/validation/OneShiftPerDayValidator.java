package org.acme.solver.validation;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.acme.model.Employee;
import org.acme.model.Shift;
import org.slf4j.Logger;

/**
 * 1일 1시프트 검증을 수행합니다.
 * - 직원이 하루에 최대 1개의 시프트만 배정받았는지 확인
 */
public class OneShiftPerDayValidator {

    /**
     * 1일 1시프트 제약을 검증합니다.
     *
     * @param shiftsByEmployee 직원별 시프트 맵
     * @param logger 사용할 로거
     * @throws ValidationException 검증 실패 시
     */
    public void validate(Map<Employee, List<Shift>> shiftsByEmployee, Logger logger) {
        for (Map.Entry<Employee, List<Shift>> entry : shiftsByEmployee.entrySet()) {
            Employee employee = entry.getKey();
            List<Shift> shifts = entry.getValue();

            Map<LocalDate, List<Shift>> shiftsByDate = shifts.stream()
                    .collect(java.util.stream.Collectors.groupingBy(s -> s.getStart().toLocalDate()));

            for (Map.Entry<LocalDate, List<Shift>> dateEntry : shiftsByDate.entrySet()) {
                if (dateEntry.getValue().size() > 1) {
                    throw new ValidationException(
                            "Employee '%s' is assigned to %d shifts on %s. " +
                                    "Maximum 1 shift per day is allowed",
                            employee.getName(), dateEntry.getValue().size(), dateEntry.getKey());
                }
            }
        }
    }
}
