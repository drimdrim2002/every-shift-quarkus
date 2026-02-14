package org.acme.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.acme.model.EmployeeSchedule;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;

/**
 * 스케줄 내보내기 코디네이터.
 * 개별 ViewExporter들을 조합하여 전체 Markdown 문서를 생성합니다.
 */
public class ScheduleExportCoordinator {

    private static final DateTimeFormatter FILE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final EmployeeViewExporter employeeViewExporter = new EmployeeViewExporter();
    private final StatisticsViewExporter statisticsViewExporter = new StatisticsViewExporter();
    private final LocationViewExporter locationViewExporter = new LocationViewExporter();
    private final TimelineViewExporter timelineViewExporter = new TimelineViewExporter();
    private final JsonScheduleExporter jsonScheduleExporter = new JsonScheduleExporter();

    /**
     * 스케줄을 Markdown 파일로 저장합니다.
     *
     * @param schedule  OptaPlanner 솔루션
     * @param outputDir 출력 디렉토리 경로
     * @return 생성된 파일의 절대 경로
     * @throws IOException 파일 생성 실패 시
     */
    public String exportToMarkdown(EmployeeSchedule schedule, String outputDir) throws IOException {
        String markdown = toMarkdownTable(schedule);

        // 디렉토리 생성
        Path dir = Paths.get(outputDir);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }

        // 파일명 생성 (schedule-20250130-103000.md)
        String fileName = String.format("schedule-%s.md",
                LocalDateTime.now().format(FILE_TIMESTAMP_FORMAT));
        Path outputPath = dir.resolve(fileName);

        // 파일 저장
        Files.writeString(outputPath, markdown);

        return outputPath.toAbsolutePath().toString();
    }

    /**
     * 스케줄을 JSON 파일로 저장합니다.
     *
     * @param schedule  OptaPlanner 솔루션
     * @param outputDir 출력 디렉토리 경로
     * @return 생성된 파일의 절대 경로
     * @throws IOException 파일 생성 실패 시
     */
    public String exportToJson(EmployeeSchedule schedule, String outputDir) throws IOException {
        String json = toJson(schedule);

        // 디렉토리 생성
        Path dir = Paths.get(outputDir);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }

        // 파일명 생성 (schedule-20250130-103000.json)
        String fileName = String.format("schedule-%s.json",
                LocalDateTime.now().format(FILE_TIMESTAMP_FORMAT));
        Path outputPath = dir.resolve(fileName);

        // 파일 저장
        Files.writeString(outputPath, json);

        return outputPath.toAbsolutePath().toString();
    }

    /**
     * 스케줄을 Markdown 테이블 문자열로 변환합니다.
     *
     * @param schedule OptaPlanner 솔루션
     * @return Markdown 형식 문자열
     */
    public String toMarkdownTable(EmployeeSchedule schedule) {
        StringBuilder sb = new StringBuilder();

        // 헤더
        buildHeaderSection(schedule, sb);

        // 1. 직원별 근무표
        sb.append(employeeViewExporter.build(schedule));

        // 2. 요약 통계
        sb.append(statisticsViewExporter.build(schedule));

        // 3. 위치별 근무표
        sb.append(locationViewExporter.build(schedule));

        // 4. 타임라인 뷰
        sb.append(timelineViewExporter.build(schedule));

        return sb.toString();
    }

    /**
     * 헤더 섹션을 생성합니다.
     */
    private void buildHeaderSection(EmployeeSchedule schedule, StringBuilder sb) {
        HardSoftScore score = schedule.getScore();
        int totalEmployees = schedule.getEmployeeList().size();
        int totalShifts = schedule.getShiftList().size();

        sb.append("# 근무표 (Employee Schedule)\n\n");
        sb.append("**점수**: ").append(score).append("\n");
        sb.append("**생성일**: ").append(LocalDateTime.now().format(DATETIME_FORMAT)).append("\n");
        sb.append("**총 직원**: ").append(totalEmployees).append("명\n");
        sb.append("**총 시프트**: ").append(totalShifts).append("개\n\n");
    }

    /**
     * 스케줄을 JSON 문자열로 변환합니다.
     *
     * @param schedule OptaPlanner 솔루션
     * @return JSON 형식 문자열
     */
    public String toJson(EmployeeSchedule schedule) {
        return jsonScheduleExporter.toJsonString(schedule);
    }
}
