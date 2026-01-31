package org.acme.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.acme.model.Employee;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ScheduleExporterTest {

    @Test
    void testToMarkdownTable_ValidSchedule_ReturnsMarkdown() {
        // Given
        EmployeeSchedule schedule = createTestSchedule();

        // When
        String markdown = ScheduleExporter.toMarkdownTable(schedule);

        // Then
        assertNotNull(markdown);
        assertFalse(markdown.isEmpty());

        // 헤더 검증
        assertTrue(markdown.contains("# 근무표 (Employee Schedule)"));
        assertTrue(markdown.contains("**점수**"));

        // 모든 4개 섹션 검증
        assertTrue(markdown.contains("## 1. 직원별 근무 현황"));
        assertTrue(markdown.contains("## 2. 요약 통계"));
        assertTrue(markdown.contains("## 3. 위치별 근무표"));
        assertTrue(markdown.contains("## 4. 타임라인 뷰"));

        // 테이블 형식 검증
        assertTrue(markdown.contains("| 직원명 |"));
        assertTrue(markdown.contains("| 홍길동 |"));
        assertTrue(markdown.contains("| 김철수 |"));
    }

    @Test
    void testToMarkdownTable_ContainsShiftInformation() {
        // Given
        EmployeeSchedule schedule = createTestSchedule();

        // When
        String markdown = ScheduleExporter.toMarkdownTable(schedule);

        // Then
        assertTrue(markdown.contains("세브란스"));
        assertTrue(markdown.contains("강남"));

        // Shift code (D/E/N) 확인
        assertTrue(markdown.contains("08-16"));
        assertTrue(markdown.contains("16-"));

        // OFF 확인
        assertTrue(markdown.contains("OFF"));
    }

    @Test
    void testToMarkdownTable_SummaryStatisticsCorrect() {
        // Given
        EmployeeSchedule schedule = createTestSchedule();

        // When
        String markdown = ScheduleExporter.toMarkdownTable(schedule);

        // Then
        // 통계 테이블 헤더 확인
        assertTrue(markdown.contains("| 직원명 | 총 근무일 | Day | Evening | Night | OFF |"));

        // 통계 데이터가 포함되어 있는지 확인
        assertTrue(markdown.contains("| 홍길동 |"));
        assertTrue(markdown.contains("| 김철수 |"));
    }

    @Test
    void testToMarkdownTable_LocationBasedView() {
        // Given
        EmployeeSchedule schedule = createTestSchedule();

        // When
        String markdown = ScheduleExporter.toMarkdownTable(schedule);

        // Then
        // 위치별 섹션 헤더 확인
        assertTrue(markdown.contains("### 세브란스"));
        assertTrue(markdown.contains("### 강남"));

        // 시간대 컬럼 확인
        assertTrue(markdown.contains("| 날짜 | 08-16 | 16-24 | 00-08 |"));
    }

    @Test
    void testToMarkdownTable_TimelineView() {
        // Given
        EmployeeSchedule schedule = createTestSchedule();

        // When
        String markdown = ScheduleExporter.toMarkdownTable(schedule);

        // Then
        // 타임라인 뷰 섹션 확인
        assertTrue(markdown.contains("### 홍길동"));
        assertTrue(markdown.contains("### 김철수"));

        // 타임라인 형식 확인 (- 날짜: CODE TIME LOCATION)
        assertTrue(markdown.contains("- 12/01:"));
        assertTrue(markdown.contains("08-16"));
    }

    @Test
    void testExportToMarkdown_ValidSchedule_CreatesFile() throws IOException {
        // Given
        EmployeeSchedule schedule = createTestSchedule();
        Path tempDir = Files.createTempDirectory("schedule-test");
        Path outputFile = null;

        try {
            // When
            String outputPath = ScheduleExporter.exportToMarkdown(schedule, tempDir.toString());
            outputFile = Paths.get(outputPath);

            // Then
            assertNotNull(outputPath);
            assertTrue(Files.exists(outputFile));

            // 파일 내용 검증
            String content = Files.readString(outputFile);
            assertTrue(content.contains("# 근무표"));
            assertTrue(content.contains("|"));
        } finally {
            // 정리
            if (outputFile != null) {
                Files.deleteIfExists(outputFile);
            }
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void testExportToMarkdown_CreatesDirectoryIfNeeded() throws IOException {
        // Given
        EmployeeSchedule schedule = createTestSchedule();
        Path tempDir = Files.createTempDirectory("schedule-test");
        Path newDir = tempDir.resolve("nested/directory");
        Path outputFile = null;

        try {
            // When
            String outputPath = ScheduleExporter.exportToMarkdown(schedule, newDir.toString());
            outputFile = Paths.get(outputPath);

            // Then
            assertTrue(Files.exists(outputFile));
            assertTrue(Files.exists(newDir));
        } finally {
            // 정리
            if (outputFile != null) {
                Files.deleteIfExists(outputFile);
            }
            Files.deleteIfExists(newDir);
            Files.deleteIfExists(newDir.getParent());
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void testToMarkdownTable_EmptySchedule_ReturnsMarkdownWithHeaders() {
        // Given
        EmployeeSchedule schedule = new EmployeeSchedule();
        schedule.setShiftList(new ArrayList<>());
        schedule.setEmployeeList(new ArrayList<>());
        schedule.setAvailabilityList(new ArrayList<>());
        schedule.setScore(HardSoftScore.ZERO);

        // When
        String markdown = ScheduleExporter.toMarkdownTable(schedule);

        // Then
        assertNotNull(markdown);
        assertTrue(markdown.contains("# 근무표"));
        assertTrue(markdown.contains("**총 직원**: 0명"));
        assertTrue(markdown.contains("**총 시프트**: 0개"));
    }

    // ========== Helper Methods ==========

    /**
     * 테스트용 기본 EmployeeSchedule 생성
     */
    private EmployeeSchedule createTestSchedule() {
        EmployeeSchedule schedule = new EmployeeSchedule();
        schedule.setScore(HardSoftScore.of(0, -5));

        // 직원 생성
        Employee emp1 = new Employee();
        emp1.setId("E001");
        emp1.setName("홍길동");

        Employee emp2 = new Employee();
        emp2.setId("E002");
        emp2.setName("김철수");

        List<Employee> employees = List.of(emp1, emp2);
        schedule.setEmployeeList(employees);
        schedule.setAvailabilityList(new ArrayList<>());

        // Shift 생성
        List<Shift> shifts = new ArrayList<>();

        LocalDate date1 = LocalDate.of(2025, 12, 1);
        LocalDate date2 = LocalDate.of(2025, 12, 2);
        LocalDate date3 = LocalDate.of(2025, 12, 3);

        // Day shift
        Shift shift1 = createShift(1L, date1.atTime(8, 0), date1.atTime(16, 0), "세브란스", emp1);
        shifts.add(shift1);

        // Evening shift
        Shift shift2 = createShift(2L, date1.atTime(16, 0), date1.atTime(23, 59), "세브란스", emp2);
        shifts.add(shift2);

        // Night shift
        Shift shift3 = createShift(3L, date2.atTime(0, 0), date2.atTime(8, 0), "강남", emp1);
        shifts.add(shift3);

        // Day shift at different location
        Shift shift4 = createShift(4L, date2.atTime(8, 0), date2.atTime(16, 0), "강남", emp2);
        shifts.add(shift4);

        // Evening shift
        Shift shift5 = createShift(5L, date3.atTime(16, 0), date3.atTime(23, 59), "세브란스", emp1);
        shifts.add(shift5);

        schedule.setShiftList(shifts);

        return schedule;
    }

    private Shift createShift(Long id, LocalDateTime start, LocalDateTime end, String location, Employee employee) {
        Shift shift = new Shift();
        shift.setId(id);
        shift.setStart(start);
        shift.setEnd(end);
        shift.setLocation(location);
        shift.setRequiredSkill("general");
        shift.setEmployee(employee);
        shift.setPinned(false);
        return shift;
    }
}
