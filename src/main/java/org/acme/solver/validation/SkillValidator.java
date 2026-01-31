package org.acme.solver.validation;

import java.util.List;
import java.util.Map;

import org.acme.model.Employee;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.slf4j.Logger;

/**
 * 스킬 요구사항 검증을 수행합니다.
 * - 직원이 해당 시프트에 필요한 스킬을 가지고 있는지 확인
 */
public class SkillValidator {

    /**
     * 스킬 요구사항을 검증합니다.
     *
     * @param schedule 검증할 스케줄
     * @param logger 사용할 로거
     * @throws ValidationException 검증 실패 시
     */
    public void validate(EmployeeSchedule schedule, Logger logger) {
        for (Shift shift : schedule.getShiftList()) {
            Employee employee = shift.getEmployee();
            if (employee != null && !employee.getSkillSet().contains(shift.getRequiredSkill())) {
                throw new ValidationException(
                        "Employee '%s' lacks required skill '%s' for shift %d at %s (%s-%s)",
                        employee.getName(), shift.getRequiredSkill(),
                        shift.getId(), shift.getLocation(), shift.getStart(), shift.getEnd());
            }
        }
    }
}
