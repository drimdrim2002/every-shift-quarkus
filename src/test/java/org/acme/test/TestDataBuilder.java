package org.acme.test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import org.acme.model.Availability;
import org.acme.model.AvailabilityType;
import org.acme.model.Employee;
import org.acme.model.Shift;

/**
 * 테스트용 도메인 객체를 쉽게 생성하는 빌더 클래스.
 */
public class TestDataBuilder {

    /**
     * Employee 빌더.
     */
    public static class EmployeeBuilder {
        private String id = "EMP-001";
        private String name = "테스트 직원";
        private Set<String> availableShift = Set.of("D", "E", "N");
        private Set<String> skillSet = Set.of("ALL");

        public static EmployeeBuilder builder() {
            return new EmployeeBuilder();
        }

        public EmployeeBuilder id(String id) {
            this.id = id;
            return this;
        }

        public EmployeeBuilder name(String name) {
            this.name = name;
            return this;
        }

        public EmployeeBuilder availableShift(Set<String> availableShift) {
            this.availableShift = availableShift;
            return this;
        }

        public EmployeeBuilder availableShift(String... shifts) {
            this.availableShift = Set.of(shifts);
            return this;
        }

        public EmployeeBuilder skillSet(Set<String> skillSet) {
            this.skillSet = skillSet;
            return this;
        }

        public EmployeeBuilder skillSet(String... skills) {
            this.skillSet = Set.of(skills);
            return this;
        }

        public Employee build() {
            Employee employee = new Employee();
            employee.setId(id);
            employee.setName(name);
            employee.setAvailableShift(new HashSet<>(availableShift));
            employee.setSkillSet(new HashSet<>(skillSet));
            return employee;
        }
    }

    /**
     * Shift 빌더.
     * 참고: supabaseId는 setter가 없으므로 직접 설정할 수 없습니다.
     */
    public static class ShiftBuilder {
        private Long id = 1L;
        private LocalDateTime start = LocalDate.now().atTime(8, 0);
        private LocalDateTime end = LocalDate.now().atTime(16, 0);
        private String location = "세브란스";
        private String requiredSkill = "general";
        private Employee employee = null;
        private boolean pinned = false;

        public static ShiftBuilder builder() {
            return new ShiftBuilder();
        }

        public ShiftBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public ShiftBuilder start(LocalDateTime start) {
            this.start = start;
            return this;
        }

        public ShiftBuilder start(LocalDate date, int hour, int minute) {
            this.start = date.atTime(hour, minute);
            return this;
        }

        public ShiftBuilder end(LocalDateTime end) {
            this.end = end;
            return this;
        }

        public ShiftBuilder end(LocalDate date, int hour, int minute) {
            this.end = date.atTime(hour, minute);
            return this;
        }

        public ShiftBuilder location(String location) {
            this.location = location;
            return this;
        }

        public ShiftBuilder requiredSkill(String requiredSkill) {
            this.requiredSkill = requiredSkill;
            return this;
        }

        public ShiftBuilder employee(Employee employee) {
            this.employee = employee;
            return this;
        }

        public ShiftBuilder pinned(boolean pinned) {
            this.pinned = pinned;
            return this;
        }

        public Shift build() {
            Shift shift = new Shift();
            shift.setId(id);
            shift.setStart(start);
            shift.setEnd(end);
            shift.setLocation(location);
            shift.setRequiredSkill(requiredSkill);
            shift.setEmployee(employee);
            shift.setPinned(pinned);
            return shift;
        }
    }

    /**
     * Availability 빌더.
     */
    public static class AvailabilityBuilder {
        private Long id = null;
        private Employee employee = null;
        private LocalDate date = LocalDate.now();
        private AvailabilityType availabilityType = AvailabilityType.DESIRED;

        public static AvailabilityBuilder builder() {
            return new AvailabilityBuilder();
        }

        public AvailabilityBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public AvailabilityBuilder employee(Employee employee) {
            this.employee = employee;
            return this;
        }

        public AvailabilityBuilder date(LocalDate date) {
            this.date = date;
            return this;
        }

        public AvailabilityBuilder date(int year, int month, int day) {
            this.date = LocalDate.of(year, month, day);
            return this;
        }

        public AvailabilityBuilder availabilityType(AvailabilityType availabilityType) {
            this.availabilityType = availabilityType;
            return this;
        }

        public Availability build() {
            Availability availability = new Availability();
            availability.setId(id);
            availability.setEmployee(employee);
            availability.setDate(date);
            availability.setAvailabilityType(availabilityType);
            return availability;
        }
    }
}
