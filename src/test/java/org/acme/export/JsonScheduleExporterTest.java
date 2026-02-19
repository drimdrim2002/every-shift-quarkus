package org.acme.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.acme.export.dto.ScheduleJsonDto;
import org.acme.export.dto.ScheduleJsonDto.DraftRangeDto;
import org.acme.export.dto.ScheduleJsonDto.EmployeeShiftsDto;
import org.acme.export.dto.ScheduleJsonDto.EmployeeStatisticsDto;
import org.acme.export.dto.ScheduleJsonDto.ShiftDetailDto;
import org.acme.model.Employee;
import org.acme.model.EmployeeSchedule;
import org.acme.model.ScheduleState;
import org.acme.model.Shift;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.score.buildin.bendable.BendableScore;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class JsonScheduleExporterTest {

    private JsonScheduleExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new JsonScheduleExporter();
    }

    @Test
    void toJson_WithValidSchedule_ReturnsValidDto() {
        // Given
        EmployeeSchedule schedule = createTestSchedule();

        // When
        ScheduleJsonDto result = exporter.toJson(schedule);

        // Then
        assertNotNull(result);
        assertNotNull(result.getMetadata());
        assertNotNull(result.getEmployees());
        assertNotNull(result.getStatistics());
    }

    @Test
    void toJson_WithNullSchedule_ReturnsEmptyDto() {
        // When
        ScheduleJsonDto result = exporter.toJson(null);

        // Then
        assertNotNull(result);
        assertNull(result.getMetadata());
        assertNull(result.getEmployees());
        assertNull(result.getStatistics());
    }

    @Test
    void toJson_Metadata_VerifiesFields() {
        // Given
        EmployeeSchedule schedule = createTestSchedule();

        // When
        ScheduleJsonDto result = exporter.toJson(schedule);

        // Then
        assertEquals(BendableScore.of(new int[] { 0 }, new int[] { -10, 0, 0 }).toString(),
                result.getMetadata().getScore());
        assertEquals(2, result.getMetadata().getTotalEmployees());
        assertEquals(5, result.getMetadata().getTotalShifts());
        assertNotNull(result.getMetadata().getGeneratedAt());
    }

    @Test
    void toJson_DraftRange_VerifiesRange() {
        // Given
        EmployeeSchedule schedule = createTestSchedule();

        // When
        ScheduleJsonDto result = exporter.toJson(schedule);
        DraftRangeDto draftRange = result.getMetadata().getDraftRange();

        // Then
        assertNotNull(draftRange);
        assertEquals(LocalDate.of(2025, 12, 1), draftRange.getStart());
        assertEquals(LocalDate.of(2025, 12, 31), draftRange.getEnd());
    }

    @Test
    void toJson_Employees_VerifiesStructure() {
        // Given
        EmployeeSchedule schedule = createTestSchedule();

        // When
        ScheduleJsonDto result = exporter.toJson(schedule);
        List<EmployeeShiftsDto> employees = result.getEmployees();

        // Then
        assertEquals(2, employees.size());

        // 첫 번째 직원 검증 (이름순 정렬: 김철수 -> 홍길동)
        EmployeeShiftsDto emp1 = employees.get(0);
        assertEquals("E002", emp1.getId());
        assertEquals("김철수", emp1.getName());
        assertNotNull(emp1.getShifts());

        // 두 번째 직원 검증
        EmployeeShiftsDto emp2 = employees.get(1);
        assertEquals("E001", emp2.getId());
        assertEquals("홍길동", emp2.getName());
    }

    @Test
    void toJson_Shifts_VerifiesShiftDetails() {
        // Given
        EmployeeSchedule schedule = createTestSchedule();

        // When
        ScheduleJsonDto result = exporter.toJson(schedule);
        List<EmployeeShiftsDto> employees = result.getEmployees();
        EmployeeShiftsDto emp2 = employees.get(1); // 홍길동은 두 번째
        List<ShiftDetailDto> shifts = emp2.getShifts();

        // Then - D 시프트 (08:00-16:00)
        ShiftDetailDto shift1 = shifts.get(0);
        assertEquals(LocalDate.of(2025, 12, 1), shift1.getDate());
        assertEquals("D", shift1.getCode());
        assertEquals("2025-12-01 09:00", shift1.getStartTime());
        assertEquals("2025-12-01 17:00", shift1.getEndTime());
        assertEquals("세브란스", shift1.getLocation());

        // E 시프트 (16:00-00:00)
        ShiftDetailDto shift2 = shifts.get(1);
        assertEquals(LocalDate.of(2025, 12, 2), shift2.getDate());
        assertEquals("E", shift2.getCode());
        assertEquals("2025-12-02 17:00", shift2.getStartTime());
        assertEquals("2025-12-02 23:59", shift2.getEndTime());

        // N 시프트 (00:00-08:00)
        ShiftDetailDto shift3 = shifts.get(2);
        assertEquals(LocalDate.of(2025, 12, 2), shift3.getDate());
        assertEquals("N", shift3.getCode());
        assertEquals("2025-12-03 00:00", shift3.getStartTime());
        assertEquals("2025-12-03 08:00", shift3.getEndTime());
    }

    @Test
    void toJson_Shifts_NightAt2300_UsesActualStartDate() {
        EmployeeSchedule schedule = createSingleShiftSchedule(
                LocalDateTime.of(2025, 12, 3, 23, 0),
                LocalDateTime.of(2025, 12, 4, 7, 0),
                "N");

        ScheduleJsonDto result = exporter.toJson(schedule);
        ShiftDetailDto shift = result.getEmployees().get(0).getShifts().get(0);

        assertEquals("N", shift.getCode());
        assertEquals(LocalDate.of(2025, 12, 3), shift.getDate());
    }

    @Test
    void toJson_Statistics_VerifiesCalculations() {
        // Given
        EmployeeSchedule schedule = createTestSchedule();

        // When
        ScheduleJsonDto result = exporter.toJson(schedule);
        List<EmployeeStatisticsDto> statistics = result.getStatistics();

        // Then
        assertEquals(2, statistics.size());

        // 첫 번째 직원 통계 (김철수): D=1, E=0, N=1, 총 2일
        EmployeeStatisticsDto stats1 = statistics.get(0);
        assertEquals("E002", stats1.getEmployeeId());
        assertEquals("김철수", stats1.getEmployeeName());
        assertEquals(2, stats1.getTotalWorkDays());
        assertEquals(1, stats1.getDayShifts());
        assertEquals(0, stats1.getEveningShifts());
        assertEquals(1, stats1.getNightShifts());

        // 두 번째 직원 통계 (홍길동): D=1, E=1, N=1, 총 3일
        EmployeeStatisticsDto stats2 = statistics.get(1);
        assertEquals("E001", stats2.getEmployeeId());
        assertEquals("홍길동", stats2.getEmployeeName());
        assertEquals(3, stats2.getTotalWorkDays());
        assertEquals(1, stats2.getDayShifts());
        assertEquals(1, stats2.getEveningShifts());
        assertEquals(1, stats2.getNightShifts());
    }

    /**
     * 테스트용 EmployeeSchedule을 생성합니다.
     */
    private EmployeeSchedule createTestSchedule() {
        EmployeeSchedule schedule = new EmployeeSchedule();

        // ScheduleState 설정
        ScheduleState scheduleState = new ScheduleState();
        scheduleState.setTenantId("test-org");
        scheduleState.setName("테스트 병원");
        scheduleState.setFirstDraftDate(LocalDate.of(2025, 12, 1));
        scheduleState.setDraftLength(30);
        scheduleState.setLastHistoricDate(LocalDate.of(2025, 11, 30));
        scheduleState.setPublishLength(4);
        schedule.setScheduleState(scheduleState);

        // 직원 생성
        Employee emp1 = new Employee();
        emp1.setId("E001");
        emp1.setName("홍길동");

        Employee emp2 = new Employee();
        emp2.setId("E002");
        emp2.setName("김철수");

        List<Employee> employees = new ArrayList<>();
        employees.add(emp1);
        employees.add(emp2);
        schedule.setEmployeeList(employees);

        // 시프트 생성
        List<Shift> shifts = new ArrayList<>();

        // emp1: D, E, N 시프트
        Shift shift1 = new Shift();
        shift1.setId(1L);
        shift1.setEmployee(emp1);
        shift1.setStart(LocalDateTime.of(2025, 12, 1, 9, 0));
        shift1.setEnd(LocalDateTime.of(2025, 12, 1, 17, 0));
        shift1.setLocation("세브란스");
        shift1.setRequiredSkill("ALL");
        shifts.add(shift1);

        Shift shift2 = new Shift();
        shift2.setId(2L);
        shift2.setEmployee(emp1);
        shift2.setStart(LocalDateTime.of(2025, 12, 2, 17, 0));
        shift2.setEnd(LocalDateTime.of(2025, 12, 2, 23, 59));
        shift2.setLocation("세브란스");
        shift2.setRequiredSkill("ALL");
        shifts.add(shift2);

        Shift shift3 = new Shift();
        shift3.setId(3L);
        shift3.setEmployee(emp1);
        shift3.setStart(LocalDateTime.of(2025, 12, 3, 0, 0));
        shift3.setEnd(LocalDateTime.of(2025, 12, 3, 8, 0));
        shift3.setLocation("세브란스");
        shift3.setRequiredSkill("ALL");
        shifts.add(shift3);

        // emp2: D, N 시프트
        Shift shift4 = new Shift();
        shift4.setId(4L);
        shift4.setEmployee(emp2);
        shift4.setStart(LocalDateTime.of(2025, 12, 1, 8, 0));
        shift4.setEnd(LocalDateTime.of(2025, 12, 1, 16, 0));
        shift4.setLocation("세브란스");
        shift4.setRequiredSkill("ALL");
        shifts.add(shift4);

        Shift shift5 = new Shift();
        shift5.setId(5L);
        shift5.setEmployee(emp2);
        shift5.setStart(LocalDateTime.of(2025, 12, 3, 1, 0));
        shift5.setEnd(LocalDateTime.of(2025, 12, 3, 7, 0));
        shift5.setLocation("세브란스");
        shift5.setRequiredSkill("ALL");
        shifts.add(shift5);

        schedule.setShiftList(shifts);

        // Availability (빈 리스트)
        schedule.setAvailabilityList(new ArrayList<>());

        // Score 설정
        schedule.setScore(BendableScore.of(new int[] { 0 }, new int[] { -10, 0, 0 }));

        return schedule;
    }

    private EmployeeSchedule createSingleShiftSchedule(LocalDateTime start, LocalDateTime end, String shiftCode) {
        EmployeeSchedule schedule = new EmployeeSchedule();

        ScheduleState scheduleState = new ScheduleState();
        scheduleState.setTenantId("test-org");
        scheduleState.setName("테스트 병원");
        scheduleState.setFirstDraftDate(LocalDate.of(2025, 12, 1));
        scheduleState.setDraftLength(30);
        schedule.setScheduleState(scheduleState);

        Employee employee = new Employee();
        employee.setId("E001");
        employee.setName("홍길동");
        schedule.setEmployeeList(List.of(employee));

        Shift shift = new Shift();
        shift.setId(1L);
        shift.setEmployee(employee);
        shift.setStart(start);
        shift.setEnd(end);
        shift.setShiftCode(shiftCode);
        shift.setLocation("세브란스");
        shift.setRequiredSkill("ALL");

        schedule.setShiftList(List.of(shift));
        schedule.setAvailabilityList(new ArrayList<>());
        schedule.setScore(BendableScore.of(new int[] { 0 }, new int[] { 0, 0, 0 }));
        return schedule;
    }
}
