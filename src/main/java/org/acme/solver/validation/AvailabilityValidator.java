package org.acme.solver.validation;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.acme.model.Availability;
import org.acme.model.AvailabilityType;
import org.acme.model.Employee;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.slf4j.Logger;

/**
 * 직원 가용성 검증을 수행합니다.
 * - UNAVAILABLE 날짜에 시프트가 배정되지 않았는지 확인
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
        // UNAVAILABLE 날짜를 맵으로 변환
        Map<String, Map<LocalDate, AvailabilityType>> availabilityMap = new HashMap<>();
        for (Availability availability : schedule.getAvailabilityList()) {
            availabilityMap
                    .computeIfAbsent(availability.getEmployee().getId(), k -> new HashMap<>())
                    .put(availability.getDate(), availability.getAvailabilityType());
        }

        for (Map.Entry<Employee, List<Shift>> entry : shiftsByEmployee.entrySet()) {
            Employee employee = entry.getKey();
            Map<LocalDate, AvailabilityType> employeeAvailability = availabilityMap.getOrDefault(employee.getId(),
                    new HashMap<>());

            for (Shift shift : entry.getValue()) {
                LocalDate shiftDate = shift.getStart().toLocalDate();
                AvailabilityType availabilityType = employeeAvailability.get(shiftDate);

                if (availabilityType == AvailabilityType.UNAVAILABLE) {
                    throw new ValidationException(
                            "Employee '%s' is UNAVAILABLE on %s but assigned to shift %d at %s",
                            employee.getName(), shiftDate, shift.getId(), shift.getLocation());
                }
            }
        }
    }
}
