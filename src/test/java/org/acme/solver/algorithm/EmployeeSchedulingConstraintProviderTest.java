package org.acme.solver.algorithm;

import java.time.LocalDate;

import org.acme.model.Availability;
import org.acme.model.AvailabilityType;
import org.acme.model.Employee;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.junit.jupiter.api.Test;
import org.optaplanner.test.api.score.stream.ConstraintVerifier;

class EmployeeSchedulingConstraintProviderTest {

    ConstraintVerifier<EmployeeSchedulingConstraintProvider, EmployeeSchedule> constraintVerifier = ConstraintVerifier.build(
            new EmployeeSchedulingConstraintProvider(), EmployeeSchedule.class, Shift.class);

    @Test
    void oneShiftPerDay() {
        Employee employee = new Employee();
        employee.setId("E1");
        employee.setName("Test Employee");
        
        LocalDate date = LocalDate.of(2025, 1, 1);
        
        Shift shift1 = new Shift();
        shift1.setId(1L);
        shift1.setEmployee(employee);
        shift1.setStart(date.atTime(9, 0));
        shift1.setEnd(date.atTime(17, 0));

        Shift shift2 = new Shift();
        shift2.setId(2L);
        shift2.setEmployee(employee);
        shift2.setStart(date.atTime(18, 0));
        shift2.setEnd(date.atTime(22, 0));

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::oneShiftPerDay)
                .given(shift1, shift2)
                .penalizesBy(1);
    }

    @Test
    void atLeast12HoursBetweenTwoShifts() {
        Employee employee = new Employee();
        employee.setId("E1");
        employee.setName("Test Employee");

        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift shift1 = new Shift();
        shift1.setId(1L);
        shift1.setEmployee(employee);
        shift1.setStart(date.atTime(9, 0));
        shift1.setEnd(date.atTime(17, 0)); // Ends 17:00

        Shift shift2 = new Shift();
        shift2.setId(2L);
        shift2.setEmployee(employee);
        shift2.setStart(date.plusDays(1).atTime(4, 0)); // Starts 04:00 next day (11 hours gap)
        shift2.setEnd(date.plusDays(1).atTime(12, 0));

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::atLeast12HoursBetweenTwoShifts)
                .given(shift1, shift2)
                .penalizesBy(60); // 12h(720m) - 11h(660m) = 60m penalty
    }

    @Test
    void undesiredDayForEmployee() {
        Employee employee = new Employee();
        employee.setId("E1");
        employee.setName("Test Employee");

        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift shift = new Shift();
        shift.setId(1L);
        shift.setEmployee(employee);
        shift.setStart(date.atTime(9, 0));
        shift.setEnd(date.atTime(17, 0)); // 480분 (8시간)

        Availability undesiredAvailability = new Availability(employee, date, AvailabilityType.UNDESIRED);

        // ConstraintVerifier는 가중치가 적용되기 전의 기본 점수를 반환합니다.
        // 실제 solver에서는 480분 × 1000 = 480,000 패널티가 적용됩니다.
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::undesiredDayForEmployee)
                .given(shift, undesiredAvailability)
                .penalizesBy(480);
    }

    @Test
    void fairShiftDistribution() {
        Employee employee = new Employee();
        employee.setId("E1");
        employee.setName("Test Employee");

        LocalDate date = LocalDate.of(2025, 1, 1);

        Shift shift1 = new Shift();
        shift1.setId(1L);
        shift1.setEmployee(employee);
        shift1.setStart(date.atTime(9, 0));
        shift1.setEnd(date.atTime(17, 0));

        Shift shift2 = new Shift();
        shift2.setId(2L);
        shift2.setEmployee(employee);
        shift2.setStart(date.plusDays(1).atTime(9, 0));
        shift2.setEnd(date.plusDays(1).atTime(17, 0));

        Shift shift3 = new Shift();
        shift3.setId(3L);
        shift3.setEmployee(employee);
        shift3.setStart(date.plusDays(2).atTime(9, 0));
        shift3.setEnd(date.plusDays(2).atTime(17, 0));

        // 예상 패널티: 3² = 9 (shiftCount²)
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::fairShiftDistribution)
                .given(shift1, shift2, shift3)
                .penalizesBy(9);
    }
}
