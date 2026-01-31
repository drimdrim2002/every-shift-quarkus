package org.acme.solver.validation;

import java.util.List;
import java.util.Map;

import org.acme.model.Employee;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.slf4j.Logger;

/**
 * 데이터 무결성 검증을 수행합니다.
 * - 모든 시프트에 직원이 배정되어 있는지 확인
 * - 시간 범위가 유효한지 확인
 */
public class DataIntegrityValidator {

    /**
     * 데이터 무결성을 검증합니다.
     *
     * @param schedule 검증할 스케줄
     * @param logger 사용할 로거
     * @throws ValidationException 검증 실패 시
     */
    public void validate(EmployeeSchedule schedule, Logger logger) {
        for (Shift shift : schedule.getShiftList()) {
            // Null employee check
            if (shift.getEmployee() == null) {
                throw new ValidationException(
                        "Shift %d has no employee assigned (start: %s, end: %s, location: %s)",
                        shift.getId(), shift.getStart(), shift.getEnd(), shift.getLocation());
            }

            // Time logic check
            if (shift.getStart().isAfter(shift.getEnd())) {
                throw new ValidationException(
                        "Shift %d has invalid time range: start %s is after end %s",
                        shift.getId(), shift.getStart(), shift.getEnd());
            }
        }
    }
}
