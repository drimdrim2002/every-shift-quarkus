package org.acme.solver.validation;

import java.util.List;
import java.util.Map;

import org.acme.model.Employee;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.slf4j.Logger;

/**
 * 직원 가용성 검증을 수행합니다.
 */
public class AvailabilityValidator {

    /**
     * 직원 가용성을 검증합니다.
     *
     * @param schedule 검증할 스케줄
     * @param shiftsByEmployee 직원별 시프트 맵
     * @param logger 사용할 로거
     * @throws ValidationException 검증 실패 시
     */
    public void validate(EmployeeSchedule schedule, Map<Employee, List<Shift>> shiftsByEmployee, Logger logger) {
        // UNAVAILABLE 타입이 제거되어 별도의 가용성 위반 검증은 불필요합니다.
        // DESIRED / UNDESIRED는 POJO 점수 평가기의 soft 제약으로 처리됩니다.
    }
}
