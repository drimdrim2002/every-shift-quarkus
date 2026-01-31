package org.acme.solver.validation;

import java.util.List;
import java.util.Map;

import org.acme.model.Employee;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.slf4j.Logger;

/**
 * 솔루션 검증을 담당하는 코디네이터 클래스.
 * 개별 Validator들을 순서대로 실행합니다.
 */
public class SolutionValidator {

    private final DataIntegrityValidator dataIntegrityValidator = new DataIntegrityValidator();
    private final SkillValidator skillValidator = new SkillValidator();
    private final OverlapValidator overlapValidator = new OverlapValidator();
    private final OneShiftPerDayValidator oneShiftPerDayValidator = new OneShiftPerDayValidator();
    private final AvailabilityValidator availabilityValidator = new AvailabilityValidator();
    private final SimultaneousLocationValidator simultaneousLocationValidator = new SimultaneousLocationValidator();

    /**
     * 솔루션을 검증합니다.
     *
     * @param schedule 검증할 스케줄
     * @param logger 사용할 로거
     * @throws ValidationException 검증 실패 시
     */
    public void validate(EmployeeSchedule schedule, Logger logger) {
        logger.info("=== Solution Validation Started ===");

        // 직원별 그룹화 (공통 데이터)
        Map<Employee, List<Shift>> shiftsByEmployee = groupShiftsByEmployee(schedule);

        // Phase 1: 데이터 무결성 검증
        dataIntegrityValidator.validate(schedule, logger);

        // Phase 2: Hard Constraint 검증
        skillValidator.validate(schedule, logger);
        overlapValidator.validate(shiftsByEmployee, logger);
        oneShiftPerDayValidator.validate(shiftsByEmployee, logger);
        availabilityValidator.validate(schedule, shiftsByEmployee, logger);

        // Phase 3: 핵심 - 동시성 검증
        simultaneousLocationValidator.validate(shiftsByEmployee, logger);

        logger.info("=== Solution Validation Completed - No Violations Found ===");
    }

    /**
     * 직원별로 시프트를 그룹화합니다.
     */
    private Map<Employee, List<Shift>> groupShiftsByEmployee(EmployeeSchedule schedule) {
        return schedule.getShiftList().stream()
                .filter(shift -> shift.getEmployee() != null)
                .collect(java.util.stream.Collectors.groupingBy(Shift::getEmployee));
    }
}
