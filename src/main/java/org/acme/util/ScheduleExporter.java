package org.acme.util;

import java.io.IOException;

import org.acme.export.ScheduleExportCoordinator;
import org.acme.model.EmployeeSchedule;

/**
 * 근무표 결과를 Markdown 형식으로 내보내는 유틸리티 클래스.
 * 내부적으로 ScheduleExportCoordinator를 위임합니다.
 *
 * @deprecated 대신 {@link org.acme.export.ScheduleExportCoordinator}를 직접 사용하세요.
 */
@Deprecated(forRemoval = false)
public class ScheduleExporter {

    private static final ScheduleExportCoordinator coordinator = new ScheduleExportCoordinator();

    /**
     * 근무표를 Markdown 파일로 저장합니다.
     *
     * @param schedule  OptaPlanner 솔루션
     * @param outputDir 출력 디렉토리 경로
     * @return 생성된 파일의 절대 경로
     * @throws IOException 파일 생성 실패 시
     * @deprecated 대신
     *             {@link ScheduleExportCoordinator#exportToMarkdown(EmployeeSchedule, String)}를
     *             사용하세요.
     */
    @Deprecated
    public static String exportToMarkdown(EmployeeSchedule schedule, String outputDir) throws IOException {
        return coordinator.exportToMarkdown(schedule, outputDir);
    }

    /**
     * 근무표를 Markdown 테이블 문자열로 변환합니다.
     *
     * @param schedule OptaPlanner 솔루션
     * @return Markdown 형식 문자열
     * @deprecated 대신
     *             {@link ScheduleExportCoordinator#toMarkdownTable(EmployeeSchedule)}를
     *             사용하세요.
     */
    @Deprecated
    public static String toMarkdownTable(EmployeeSchedule schedule) {
        return coordinator.toMarkdownTable(schedule);
    }

    /**
     * 근무표를 JSON 파일로 저장합니다.
     *
     * @param schedule  OptaPlanner 솔루션
     * @param outputDir 출력 디렉토리 경로
     * @return 생성된 파일의 절대 경로
     * @throws IOException 파일 생성 실패 시
     * @deprecated 대신
     *             {@link ScheduleExportCoordinator#exportToJson(EmployeeSchedule, String)}를
     *             사용하세요.
     */
    @Deprecated
    public static String exportToJson(EmployeeSchedule schedule, String outputDir) throws IOException {
        return coordinator.exportToJson(schedule, outputDir);
    }

    /**
     * 근무표를 JSON 문자열로 변환합니다.
     *
     * @param schedule OptaPlanner 솔루션
     * @return JSON 형식 문자열
     * @deprecated 대신 {@link ScheduleExportCoordinator#toJson(EmployeeSchedule)}를
     *             사용하세요.
     */
    @Deprecated
    public static String toJson(EmployeeSchedule schedule) {
        return coordinator.toJson(schedule);
    }
}
