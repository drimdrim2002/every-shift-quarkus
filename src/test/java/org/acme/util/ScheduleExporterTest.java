package org.acme.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.acme.model.Employee;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.acme.test.TestDataBuilder;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.score.buildin.bendable.BendableScore;

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
        schedule.setScore(BendableScore.zero(1, 3));

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
        schedule.setScore(BendableScore.of(new int[]{0}, new int[]{0, -5, 0}));

        // 직원 생성 (TestDataBuilder 사용)
        Employee emp1 = TestDataBuilder.EmployeeBuilder.builder()
                .id("E001")
                .name("홍길동")
                .build();

        Employee emp2 = TestDataBuilder.EmployeeBuilder.builder()
                .id("E002")
                .name("김철수")
                .build();

        List<Employee> employees = List.of(emp1, emp2);
        schedule.setEmployeeList(employees);
        schedule.setAvailabilityList(new ArrayList<>());

        // Shift 생성 (TestDataBuilder 사용)
        List<Shift> shifts = new ArrayList<>();

        LocalDate date1 = LocalDate.of(2025, 12, 1);
        LocalDate date2 = LocalDate.of(2025, 12, 2);
        LocalDate date3 = LocalDate.of(2025, 12, 3);

        // Day shift
        shifts.add(TestDataBuilder.ShiftBuilder.builder()
                .id(1L).start(date1, 8, 0).end(date1, 16, 0)
                .location("세브란스").employee(emp1).build());

        // Evening shift
        shifts.add(TestDataBuilder.ShiftBuilder.builder()
                .id(2L).start(date1, 16, 0).end(date1, 23, 59)
                .location("세브란스").employee(emp2).build());

        // Night shift
        shifts.add(TestDataBuilder.ShiftBuilder.builder()
                .id(3L).start(date2, 0, 0).end(date2, 8, 0)
                .location("강남").employee(emp1).build());

        // Day shift at different location
        shifts.add(TestDataBuilder.ShiftBuilder.builder()
                .id(4L).start(date2, 8, 0).end(date2, 16, 0)
                .location("강남").employee(emp2).build());

        // Evening shift
        shifts.add(TestDataBuilder.ShiftBuilder.builder()
                .id(5L).start(date3, 16, 0).end(date3, 23, 59)
                .location("세브란스").employee(emp1).build());

        schedule.setShiftList(shifts);

        return schedule;
    }
}
