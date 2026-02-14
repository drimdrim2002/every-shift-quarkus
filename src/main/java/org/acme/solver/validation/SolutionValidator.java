package org.acme.solver.validation;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.acme.model.AvailabilityType;
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

        // Soft 제약 진단 로깅 (예외를 던지지 않고 관측 정보만 제공)
        logUndesiredSoftDiagnostics(schedule, logger);

        logger.info("=== Solution Validation Completed - No hard-constraint violations found ===");
    }

    /**
     * 직원별로 시프트를 그룹화합니다.
     */
    private Map<Employee, List<Shift>> groupShiftsByEmployee(EmployeeSchedule schedule) {
        return schedule.getShiftList().stream()
                .filter(shift -> shift.getEmployee() != null)
                .collect(java.util.stream.Collectors.groupingBy(Shift::getEmployee));
    }

    private void logUndesiredSoftDiagnostics(EmployeeSchedule schedule, Logger logger) {
        Map<String, Set<LocalDate>> undesiredDatesByEmployeeId = new HashMap<>();
        if (schedule.getAvailabilityList() != null) {
            schedule.getAvailabilityList().stream()
                    .filter(availability -> availability.getEmployee() != null)
                    .filter(availability -> availability.getAvailabilityType() == AvailabilityType.UNDESIRED)
                    .forEach(availability -> undesiredDatesByEmployeeId
                            .computeIfAbsent(availability.getEmployee().getId(), key -> new HashSet<>())
                            .add(availability.getDate()));
        }

        int undesiredMatchCount = 0;
        int undesiredPenaltyMinutes = 0;
        if (schedule.getShiftList() != null) {
            for (Shift shift : schedule.getShiftList()) {
                Employee employee = shift.getEmployee();
                if (employee == null) {
                    continue;
                }

                Set<LocalDate> undesiredDates = undesiredDatesByEmployeeId.get(employee.getId());
                if (undesiredDates == null) {
                    continue;
                }

                if (undesiredDates.contains(shift.getStart().toLocalDate())) {
                    undesiredMatchCount++;
                    undesiredPenaltyMinutes += (int) Duration.between(shift.getStart(), shift.getEnd()).toMinutes();
                }
            }
        }

        logger.info(
                "Soft diagnostics: undesired_match_count={}, undesired_penalty_minutes={}",
                undesiredMatchCount, undesiredPenaltyMinutes);
    }
}
