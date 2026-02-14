package org.acme.export;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.acme.model.Employee;
import org.acme.model.EmployeeSchedule;
import org.acme.model.ScheduleState;
import org.acme.model.Shift;
import org.acme.test.TestDataBuilder;
import org.junit.jupiter.api.Test;

class EmployeeViewExporterTest {

    private final EmployeeViewExporter exporter = new EmployeeViewExporter();

    @Test
    void build_UsesDraftRangeFromScheduleState_CodeOnly_BlankCellAndMultiShift() {
        LocalDate beforeDraftDate = LocalDate.of(2025, 11, 30);
        LocalDate draftStartDate = LocalDate.of(2025, 12, 1);
        LocalDate draftEndDate = LocalDate.of(2025, 12, 31);
        LocalDate afterDraftDate = LocalDate.of(2026, 1, 1);

        Employee emp1 = TestDataBuilder.EmployeeBuilder.builder()
                .id("E001")
                .name("홍길동")
                .build();

        Employee emp2 = TestDataBuilder.EmployeeBuilder.builder()
                .id("E002")
                .name("김철수")
                .build();

        List<Shift> shifts = new ArrayList<>();
        shifts.add(TestDataBuilder.ShiftBuilder.builder()
                .id(1L).start(beforeDraftDate, 8, 0).end(beforeDraftDate, 16, 0)
                .location("세브란스").employee(emp1).build());
        shifts.add(TestDataBuilder.ShiftBuilder.builder()
                .id(2L).start(draftStartDate, 8, 0).end(draftStartDate, 16, 0)
                .location("세브란스").employee(emp1).build());
        shifts.add(TestDataBuilder.ShiftBuilder.builder()
                .id(3L).start(draftStartDate, 16, 0).end(draftStartDate, 23, 0)
                .location("강남").employee(emp1).build());
        shifts.add(TestDataBuilder.ShiftBuilder.builder()
                .id(4L).start(draftEndDate, 21, 0).end(draftEndDate, 23, 0)
                .location("강남").employee(emp2).build());
        shifts.add(TestDataBuilder.ShiftBuilder.builder()
                .id(5L).start(afterDraftDate, 8, 0).end(afterDraftDate, 16, 0)
                .location("세브란스").employee(emp1).build());

        EmployeeSchedule schedule = new EmployeeSchedule();
        schedule.setEmployeeList(List.of(emp1, emp2));
        schedule.setShiftList(shifts);
        schedule.setAvailabilityList(new ArrayList<>());
        schedule.setScheduleState(createScheduleState(LocalDate.of(2025, 12, 1), 31));

        String markdown = exporter.build(schedule);

        assertTrue(markdown.contains("## 1. 직원별 근무 현황"));
        assertTrue(markdown.contains(draftStartDate.toString()));
        assertTrue(markdown.contains(draftEndDate.toString()));
        assertFalse(markdown.contains(beforeDraftDate.toString()));
        assertFalse(markdown.contains(afterDraftDate.toString()));

        assertTrue(markdown.contains("D<br>E"));
        assertTrue(markdown.contains("N"));

        assertFalse(markdown.contains("OFF"));
        assertFalse(markdown.contains("08-16"));
        assertFalse(markdown.contains("16-24"));
        assertFalse(markdown.contains("00-08"));
        assertFalse(markdown.contains("세브란스"));
        assertFalse(markdown.contains("강남"));
    }

    @Test
    void build_UsesCurrentMonthWhenScheduleStateMissing() {
        LocalDate currentMonthStart = LocalDate.now().withDayOfMonth(1);
        LocalDate currentDate = currentMonthStart.plusDays(4);
        LocalDate nextMonthDate = currentMonthStart.plusMonths(1).plusDays(4);

        Employee emp = TestDataBuilder.EmployeeBuilder.builder()
                .id("E001")
                .name("홍길동")
                .build();

        List<Shift> shifts = new ArrayList<>();
        shifts.add(TestDataBuilder.ShiftBuilder.builder()
                .id(1L).start(currentDate, 8, 0).end(currentDate, 16, 0)
                .location("세브란스").employee(emp).build());
        shifts.add(TestDataBuilder.ShiftBuilder.builder()
                .id(2L).start(nextMonthDate, 16, 0).end(nextMonthDate, 23, 0)
                .location("강남").employee(emp).build());

        EmployeeSchedule schedule = new EmployeeSchedule();
        schedule.setEmployeeList(List.of(emp));
        schedule.setShiftList(shifts);
        schedule.setAvailabilityList(new ArrayList<>());

        String markdown = exporter.build(schedule);

        assertTrue(markdown.contains(currentDate.toString()));
        assertFalse(markdown.contains(nextMonthDate.toString()));
    }

    @Test
    void build_UsesDateRangeOverloadForDeterministicFiltering() {
        LocalDate startInclusive = LocalDate.of(2025, 12, 1);
        LocalDate endExclusive = LocalDate.of(2026, 1, 1);

        Employee emp = TestDataBuilder.EmployeeBuilder.builder()
                .id("E001")
                .name("홍길동")
                .build();

        List<Shift> shifts = new ArrayList<>();
        shifts.add(TestDataBuilder.ShiftBuilder.builder()
                .id(1L).start(LocalDate.of(2025, 11, 30), 8, 0).end(LocalDate.of(2025, 11, 30), 16, 0)
                .location("세브란스").employee(emp).build());
        shifts.add(TestDataBuilder.ShiftBuilder.builder()
                .id(2L).start(LocalDate.of(2025, 12, 15), 8, 0).end(LocalDate.of(2025, 12, 15), 16, 0)
                .location("세브란스").employee(emp).build());
        shifts.add(TestDataBuilder.ShiftBuilder.builder()
                .id(3L).start(LocalDate.of(2026, 1, 1), 8, 0).end(LocalDate.of(2026, 1, 1), 16, 0)
                .location("세브란스").employee(emp).build());

        EmployeeSchedule schedule = new EmployeeSchedule();
        schedule.setEmployeeList(List.of(emp));
        schedule.setShiftList(shifts);
        schedule.setAvailabilityList(new ArrayList<>());

        String markdown = exporter.build(schedule, startInclusive, endExclusive);

        assertTrue(markdown.contains("2025-12-15"));
        assertFalse(markdown.contains("2025-11-30"));
        assertFalse(markdown.contains("2026-01-01"));
    }

    private ScheduleState createScheduleState(LocalDate firstDraftDate, int draftLength) {
        ScheduleState scheduleState = new ScheduleState();
        scheduleState.setFirstDraftDate(firstDraftDate);
        scheduleState.setDraftLength(draftLength);
        return scheduleState;
    }
}
