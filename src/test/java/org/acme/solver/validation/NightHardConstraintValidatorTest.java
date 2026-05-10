package org.acme.solver.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.util.Arrays;
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
    void validate_allowsThreeConsecutiveNightShifts() {
        Employee employee = employee("emp-1", "테스터");

        Map<Employee, List<Shift>> shiftsByEmployee = Map.of(
                employee,
                List.of(
                        shift(1L, "N", LocalDateTime.of(2026, 2, 1, 0, 0), LocalDateTime.of(2026, 2, 1, 8, 0), employee),
                        shift(2L, "N", LocalDateTime.of(2026, 2, 2, 0, 0), LocalDateTime.of(2026, 2, 2, 8, 0), employee),
                        shift(3L, "N", LocalDateTime.of(2026, 2, 3, 0, 0), LocalDateTime.of(2026, 2, 3, 8, 0), employee)));

        assertDoesNotThrow(() -> validator.validate(shiftsByEmployee, LoggerFactory.getLogger(getClass())));
    }

    @Test
    void validate_throwsWhenFourConsecutiveNightShiftsExist() {
        Employee employee = employee("emp-1", "테스터");

        Map<Employee, List<Shift>> shiftsByEmployee = Map.of(
                employee,
                List.of(
                        shift(1L, "N", LocalDateTime.of(2026, 2, 1, 0, 0), LocalDateTime.of(2026, 2, 1, 8, 0), employee),
                        shift(2L, "N", LocalDateTime.of(2026, 2, 2, 0, 0), LocalDateTime.of(2026, 2, 2, 8, 0), employee),
                        shift(3L, "N", LocalDateTime.of(2026, 2, 3, 0, 0), LocalDateTime.of(2026, 2, 3, 8, 0), employee),
                        shift(4L, "N", LocalDateTime.of(2026, 2, 4, 0, 0), LocalDateTime.of(2026, 2, 4, 8, 0), employee)));

        assertThrows(ValidationException.class,
                () -> validator.validate(shiftsByEmployee, LoggerFactory.getLogger(getClass())));
    }

    @Test
    void validate_allowsDayShiftStartsWithin32HoursAfterNightShiftBecauseItIsSoft() {
        Employee employee = employee("emp-1", "테스터");

        Map<Employee, List<Shift>> shiftsByEmployee = Map.of(
                employee,
                List.of(
                        shift(1L, "N", LocalDateTime.of(2026, 2, 1, 0, 0), LocalDateTime.of(2026, 2, 1, 8, 0), employee),
                        shift(2L, "D", LocalDateTime.of(2026, 2, 2, 8, 0), LocalDateTime.of(2026, 2, 2, 16, 0), employee)));

        assertDoesNotThrow(() -> validator.validate(shiftsByEmployee, LoggerFactory.getLogger(getClass())));
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

    @Test
    void validate_allowsNextShiftStartsWithin48HoursAfterTwoConsecutiveNightShiftsBecauseItIsSoft() {
        Employee employee = employee("emp-1", "테스터");

        Map<Employee, List<Shift>> shiftsByEmployee = Map.of(
                employee,
                List.of(
                        shift(1L, "N", LocalDateTime.of(2026, 2, 2, 0, 0), LocalDateTime.of(2026, 2, 2, 8, 0), employee),
                        shift(2L, "N", LocalDateTime.of(2026, 2, 3, 0, 0), LocalDateTime.of(2026, 2, 3, 8, 0), employee),
                        shift(3L, "E", LocalDateTime.of(2026, 2, 5, 7, 59), LocalDateTime.of(2026, 2, 5, 15, 59), employee)));

        assertDoesNotThrow(() -> validator.validate(shiftsByEmployee, LoggerFactory.getLogger(getClass())));
    }

    @Test
    void validate_allowsExactly48HoursAfterTwoConsecutiveNightShifts() {
        Employee employee = employee("emp-1", "테스터");

        Map<Employee, List<Shift>> shiftsByEmployee = Map.of(
                employee,
                List.of(
                        shift(1L, "N", LocalDateTime.of(2026, 2, 2, 0, 0), LocalDateTime.of(2026, 2, 2, 8, 0), employee),
                        shift(2L, "N", LocalDateTime.of(2026, 2, 3, 0, 0), LocalDateTime.of(2026, 2, 3, 8, 0), employee),
                        shift(3L, "D", LocalDateTime.of(2026, 2, 5, 8, 0), LocalDateTime.of(2026, 2, 5, 16, 0), employee)));

        assertDoesNotThrow(() -> validator.validate(shiftsByEmployee, LoggerFactory.getLogger(getClass())));
    }

    @Test
    void validate_throwsWhenActualStartMonthHas16NightShifts() {
        Employee employee = employee("emp-1", "테스터");

        Map<Employee, List<Shift>> shiftsByEmployee = Map.of(
                employee,
                Arrays.asList(everyOtherDayNightShifts(employee, LocalDateTime.of(2026, 3, 1, 0, 0), 16)));

        assertThrows(ValidationException.class,
                () -> validator.validate(shiftsByEmployee, LoggerFactory.getLogger(getClass())));
    }

    @Test
    void validate_allows15NightShiftsInActualStartMonth() {
        Employee employee = employee("emp-1", "테스터");

        Map<Employee, List<Shift>> shiftsByEmployee = Map.of(
                employee,
                Arrays.asList(everyOtherDayNightShifts(employee, LocalDateTime.of(2026, 3, 1, 0, 0), 15)));

        assertDoesNotThrow(() -> validator.validate(shiftsByEmployee, LoggerFactory.getLogger(getClass())));
    }

    @Test
    void validate_countsMonthlyNightShiftsByActualStartMonthIndependently() {
        Employee employee = employee("emp-1", "테스터");

        Map<Employee, List<Shift>> shiftsByEmployee = Map.of(
                employee,
                List.of(
                        shift(1L, "N", LocalDateTime.of(2026, 3, 1, 0, 0), LocalDateTime.of(2026, 3, 1, 8, 0), employee),
                        shift(2L, "N", LocalDateTime.of(2026, 4, 1, 0, 0), LocalDateTime.of(2026, 4, 1, 8, 0), employee)));

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

    private static Shift[] everyOtherDayNightShifts(Employee employee, LocalDateTime firstStart, int count) {
        Shift[] shifts = new Shift[count];
        for (int index = 0; index < count; index++) {
            LocalDateTime start = firstStart.plusDays(index * 2L);
            shifts[index] = shift((long) index + 1, "N", start, start.plusHours(8), employee);
        }
        return shifts;
    }
}
