package org.acme.solver.validation;

import java.util.List;
import java.util.Map;

import org.acme.converter.ShiftOverlapCalculator;
import org.acme.model.Employee;
import org.acme.model.Shift;
import org.slf4j.Logger;

/**
 * 시간 중복 검증을 수행합니다.
 * - 직원이 중복되는 시간대에 근무하지 않는지 확인
 */
public class OverlapValidator {

    /**
     * 시간 중복을 검증합니다.
     *
     * @param shiftsByEmployee 직원별 시프트 맵
     * @param logger 사용할 로거
     * @throws ValidationException 검증 실패 시
     */
    public void validate(Map<Employee, List<Shift>> shiftsByEmployee, Logger logger) {
        for (Map.Entry<Employee, List<Shift>> entry : shiftsByEmployee.entrySet()) {
            Employee employee = entry.getKey();
            List<Shift> shifts = entry.getValue();

            for (int i = 0; i < shifts.size(); i++) {
                for (int j = i + 1; j < shifts.size(); j++) {
                    Shift shift1 = shifts.get(i);
                    Shift shift2 = shifts.get(j);

                    int overlapMinutes = ShiftOverlapCalculator.getMinuteOverlap(shift1, shift2);
                    if (overlapMinutes > 0) {
                        throw new ValidationException(
                                "Employee '%s' has overlapping shifts: " +
                                        "Shift %d (%s-%s at %s) and Shift %d (%s-%s at %s) " +
                                        "overlap by %d minutes",
                                employee.getName(),
                                shift1.getId(), shift1.getStart(), shift1.getEnd(), shift1.getLocation(),
                                shift2.getId(), shift2.getStart(), shift2.getEnd(), shift2.getLocation(),
                                overlapMinutes);
                    }
                }
            }
        }
    }
}
