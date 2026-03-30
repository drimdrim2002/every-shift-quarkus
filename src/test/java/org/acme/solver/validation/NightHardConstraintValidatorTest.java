package org.acme.solver.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.acme.model.Employee;
import org.acme.model.Shift;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class NightHardConstraintValidatorTest {

    private final NightHardConstraintValidator validator = new NightHardConstraintValidator();

    @Test
    void validate_throwsWhenThreeConsecutiveNightShiftsExist() {
        Employee employee = employee("emp-1", "테스터");

        Map<Employee, List<Shift>> shiftsByEmployee = Map.of(
                employee,
                List.of(
                        shift(1L, "N", LocalDateTime.of(2026, 2, 1, 0, 0), LocalDateTime.of(2026, 2, 1, 8, 0), employee),
                        shift(2L, "N", LocalDateTime.of(2026, 2, 2, 0, 0), LocalDateTime.of(2026, 2, 2, 8, 0), employee),
                        shift(3L, "N", LocalDateTime.of(2026, 2, 3, 0, 0), LocalDateTime.of(2026, 2, 3, 8, 0), employee)));

        assertThrows(ValidationException.class,
                () -> validator.validate(shiftsByEmployee, LoggerFactory.getLogger(getClass())));
    }

    @Test
    void validate_throwsWhenDayShiftStartsWithin32HoursAfterNightShift() {
        Employee employee = employee("emp-1", "테스터");

        Map<Employee, List<Shift>> shiftsByEmployee = Map.of(
                employee,
                List.of(
                        shift(1L, "N", LocalDateTime.of(2026, 2, 1, 0, 0), LocalDateTime.of(2026, 2, 1, 8, 0), employee),
                        shift(2L, "D", LocalDateTime.of(2026, 2, 2, 8, 0), LocalDateTime.of(2026, 2, 2, 16, 0), employee)));

        assertThrows(ValidationException.class,
                () -> validator.validate(shiftsByEmployee, LoggerFactory.getLogger(getClass())));
    }

    @Test
    void validate_allowsCompliantNightSchedule() {
        Employee employee = employee("emp-1", "테스터");

        Map<Employee, List<Shift>> shiftsByEmployee = Map.of(
                employee,
                List.of(
                        shift(1L, "N", LocalDateTime.of(2026, 2, 1, 0, 0), LocalDateTime.of(2026, 2, 1, 8, 0), employee),
                        shift(2L, "N", LocalDateTime.of(2026, 2, 3, 0, 0), LocalDateTime.of(2026, 2, 3, 8, 0), employee),
                        shift(3L, "D", LocalDateTime.of(2026, 2, 4, 16, 0), LocalDateTime.of(2026, 2, 5, 0, 0), employee)));

        assertDoesNotThrow(() -> validator.validate(shiftsByEmployee, LoggerFactory.getLogger(getClass())));
    }

    private static Employee employee(String id, String name) {
        return new Employee(id, name, Set.of("D", "N"), Set.of("ALL"));
    }

    private static Shift shift(Long id, String shiftCode, LocalDateTime start, LocalDateTime end, Employee employee) {
        Shift shift = new Shift(id, "shift-" + id, start, end, "ward-a", "ALL", employee, false);
        shift.setShiftCode(shiftCode);
        return shift;
    }
}
