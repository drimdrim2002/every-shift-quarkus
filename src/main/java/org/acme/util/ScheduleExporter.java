package org.acme.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.acme.model.Employee;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;

/**
 * 근무표 결과를 Markdown 형식으로 내보내는 유틸리티 클래스
 */
public class ScheduleExporter {

    private static final DateTimeFormatter FILE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd");
    private static final DateTimeFormatter DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 근무표를 Markdown 파일로 저장
     *
     * @param schedule OptaPlanner 솔루션
     * @param outputDir 출력 디렉토리 경로
     * @return 생성된 파일의 절대 경로
     * @throws IOException 파일 생성 실패 시
     */
    public static String exportToMarkdown(EmployeeSchedule schedule, String outputDir) throws IOException {
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
     * 근무표를 Markdown 테이블 문자열로 변환
     *
     * @param schedule OptaPlanner 솔루션
     * @return Markdown 형식 문자열
     */
    public static String toMarkdownTable(EmployeeSchedule schedule) {
        StringBuilder sb = new StringBuilder();

        // 헤더
        buildHeaderSection(schedule, sb);

        // 1. 직원별 근무표
        buildEmployeeScheduleSection(schedule, sb);

        // 2. 요약 통계
        buildSummaryStatisticsSection(schedule, sb);

        // 3. 위치별 근무표
        buildLocationBasedViewSection(schedule, sb);

        // 4. 타임라인 뷰
        buildTimelineViewSection(schedule, sb);

        return sb.toString();
    }

    // ========== Section Builders ==========

    private static void buildHeaderSection(EmployeeSchedule schedule, StringBuilder sb) {
        HardSoftScore score = schedule.getScore();
        int totalEmployees = schedule.getEmployeeList().size();
        int totalShifts = schedule.getShiftList().size();

        sb.append("# 근무표 (Employee Schedule)\n\n");
        sb.append("**점수**: ").append(score).append("\n");
        sb.append("**생성일**: ").append(LocalDateTime.now().format(DATETIME_FORMAT)).append("\n");
        sb.append("**총 직원**: ").append(totalEmployees).append("명\n");
        sb.append("**총 시프트**: ").append(totalShifts).append("개\n\n");
    }

    private static void buildEmployeeScheduleSection(EmployeeSchedule schedule, StringBuilder sb) {
        sb.append("## 1. 직원별 근무 현황\n\n");

        // 날짜 추출 (정렬됨)
        Set<LocalDate> dates = schedule.getShiftList().stream()
                .map(shift -> shift.getStart().toLocalDate())
                .collect(Collectors.toCollection(TreeSet::new));

        // 직원별 날짜별 Shift 매핑
        Map<String, Map<LocalDate, List<Shift>>> employeeShiftMap = buildEmployeeShiftMap(schedule);

        // 직원 이름순 정렬
        List<Employee> sortedEmployees = schedule.getEmployeeList().stream()
                .sorted(Comparator.comparing(Employee::getName))
                .toList();

        // 테이블 헤더
        sb.append("| 직원명 |");
        for (LocalDate date : dates) {
            sb.append(" ").append(date).append(" |");
        }
        sb.append("\n|--------|");
        for (int i = 0; i < dates.size(); i++) {
            sb.append("------------|");
        }
        sb.append("\n");

        // 테이블 행
        for (Employee employee : sortedEmployees) {
            sb.append("| ").append(employee.getName()).append(" |");

            Map<LocalDate, List<Shift>> dateShiftMap = employeeShiftMap.get(employee.getId());

            for (LocalDate date : dates) {
                List<Shift> shifts = dateShiftMap != null ? dateShiftMap.get(date) : null;
                sb.append(" ").append(formatShiftCell(shifts)).append(" |");
            }
            sb.append("\n");
        }

        sb.append("\n");
    }

    private static void buildSummaryStatisticsSection(EmployeeSchedule schedule, StringBuilder sb) {
        sb.append("## 2. 요약 통계\n\n");

        // 직원별 Shift 매핑
        Map<String, Map<LocalDate, List<Shift>>> employeeShiftMap = buildEmployeeShiftMap(schedule);

        // 전체 날짜 범위 계산
        Set<LocalDate> allDates = schedule.getShiftList().stream()
                .map(shift -> shift.getStart().toLocalDate())
                .collect(Collectors.toSet());

        int totalDays = allDates.size();

        // 테이블 헤더
        sb.append("| 직원명 | 총 근무일 | Day | Evening | Night | OFF |\n");
        sb.append("|--------|-----------|-----|---------|-------|-----|\n");

        // 직원 이름순 정렬
        List<Employee> sortedEmployees = schedule.getEmployeeList().stream()
                .sorted(Comparator.comparing(Employee::getName))
                .toList();

        // 통계 계산 및 행 생성
        for (Employee employee : sortedEmployees) {
            Map<LocalDate, List<Shift>> dateShiftMap = employeeShiftMap.get(employee.getId());

            int workDays = 0;
            int dayShifts = 0;
            int eveningShifts = 0;
            int nightShifts = 0;

            if (dateShiftMap != null) {
                for (List<Shift> shifts : dateShiftMap.values()) {
                    if (shifts != null && !shifts.isEmpty()) {
                        workDays++;
                        for (Shift shift : shifts) {
                            String code = extractShiftCode(shift);
                            switch (code) {
                                case "D" -> dayShifts++;
                                case "E" -> eveningShifts++;
                                case "N" -> nightShifts++;
                            }
                        }
                    }
                }
            }

            int offDays = totalDays - workDays;

            sb.append("| ").append(employee.getName()).append(" |")
                    .append(" ").append(workDays).append(" |")
                   .append(" ").append(dayShifts).append(" |")
                   .append(" ").append(eveningShifts).append(" |")
                   .append(" ").append(nightShifts).append(" |")
                    .append(" ").append(offDays).append(" |\n");
        }

        sb.append("\n");
    }

    private static void buildLocationBasedViewSection(EmployeeSchedule schedule, StringBuilder sb) {
        sb.append("## 3. 위치별 근무표\n\n");

        // 위치별 그룹화
        Map<String, List<Shift>> shiftsByLocation = schedule.getShiftList().stream()
                .collect(Collectors.groupingBy(Shift::getLocation));

        // 날짜 추출
        Set<LocalDate> dates = schedule.getShiftList().stream()
                .map(shift -> shift.getStart().toLocalDate())
                .collect(Collectors.toCollection(TreeSet::new));

        // 각 위치별 테이블 생성
        for (Map.Entry<String, List<Shift>> entry : shiftsByLocation.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {

            String location = entry.getKey();
            sb.append("### ").append(location).append("\n\n");

            // 시간대별 컬럼 정의 (08-16, 16-24, 00-08)
            List<String> timeSlots = List.of("08-16", "16-24", "00-08");

            // 테이블 헤더
            sb.append("| 날짜 |");
            for (String timeSlot : timeSlots) {
                sb.append(" ").append(timeSlot).append(" |");
            }
            sb.append("\n|------|");
            for (int i = 0; i < timeSlots.size(); i++) {
                sb.append("-------|");
            }
            sb.append("\n");

            // 날짜별 직원 배정 매핑
            Map<LocalDate, Map<String, List<String>>> dateLocationMap = new HashMap<>();

            for (Shift shift : entry.getValue()) {
                if (shift.getEmployee() != null) {
                    LocalDate date = shift.getStart().toLocalDate();
                    String timeSlot = determineTimeSlot(shift);

                    dateLocationMap
                            .computeIfAbsent(date, d -> new HashMap<>())
                            .computeIfAbsent(timeSlot, ts -> new ArrayList<>())
                            .add(shift.getEmployee().getName());
                }
            }

            // 테이블 행 (날짜순)
            for (LocalDate date : dates) {
                sb.append("| ").append(date).append(" |");

                Map<String, List<String>> timeSlotMap = dateLocationMap.get(date);

                for (String timeSlot : timeSlots) {
                    List<String> employees = timeSlotMap != null ? timeSlotMap.get(timeSlot) : null;

                    if (employees != null && !employees.isEmpty()) {
                        sb.append(" ").append(String.join(", ", employees)).append(" |");
                    } else {
                        sb.append(" - |");
                    }
                }
                sb.append("\n");
            }

            sb.append("\n");
        }
    }

    private static void buildTimelineViewSection(EmployeeSchedule schedule, StringBuilder sb) {
        sb.append("## 4. 타임라인 뷰\n\n");

        // 직원별 Shift 매핑
        Map<String, Map<LocalDate, List<Shift>>> employeeShiftMap = buildEmployeeShiftMap(schedule);

        // 직원 이름순 정렬
        List<Employee> sortedEmployees = schedule.getEmployeeList().stream()
                .sorted(Comparator.comparing(Employee::getName))
                .toList();

        for (Employee employee : sortedEmployees) {
            sb.append("### ").append(employee.getName()).append("\n\n");

            Map<LocalDate, List<Shift>> dateShiftMap = employeeShiftMap.get(employee.getId());

            if (dateShiftMap == null || dateShiftMap.isEmpty()) {
                sb.append("배정된 시프트가 없습니다.\n\n");
                continue;
            }

            // 날짜순 정렬
            List<LocalDate> sortedDates = dateShiftMap.keySet().stream()
                    .sorted()
                    .toList();

            for (LocalDate date : sortedDates) {
                List<Shift> shifts = dateShiftMap.get(date);

                if (shifts == null || shifts.isEmpty()) {
                    sb.append("- ").append(date.format(DATE_FORMAT)).append(": OFF\n");
                } else {
                    // 시작 시간순 정렬
                    shifts.sort(Comparator.comparing(Shift::getStart));

                    for (Shift shift : shifts) {
                        String code = extractShiftCode(shift);
                        String time = String.format("%s-%s",
                                shift.getStart().toLocalTime().truncatedTo(ChronoUnit.HOURS),
                                shift.getEnd().toLocalTime().truncatedTo(ChronoUnit.HOURS));

                        sb.append("- ").append(date.format(DATE_FORMAT))
                                .append(": ").append(code)
                                .append(" ").append(time)
                                .append(" ").append(shift.getLocation())
                                .append("\n");
                    }
                }
            }

            sb.append("\n");
        }
    }

    // ========== Helper Methods ==========

    /**
     * 직원별 날짜별 Shift 맵 생성
     */
    private static Map<String, Map<LocalDate, List<Shift>>> buildEmployeeShiftMap(EmployeeSchedule schedule) {
        Map<String, Map<LocalDate, List<Shift>>> employeeShiftMap = new HashMap<>();

        // 초기화
        for (Employee employee : schedule.getEmployeeList()) {
            employeeShiftMap.put(employee.getId(), new HashMap<>());
        }

        // Shift 할당
        for (Shift shift : schedule.getShiftList()) {
            if (shift.getEmployee() != null) {
                String empId = shift.getEmployee().getId();
                LocalDate date = shift.getStart().toLocalDate();

                employeeShiftMap.get(empId)
                        .computeIfAbsent(date, d -> new ArrayList<>())
                        .add(shift);
            }
        }

        return employeeShiftMap;
    }

    /**
     * ShiftCode 추출 (D/E/N)
     */
    private static String extractShiftCode(Shift shift) {
        LocalTime start = shift.getStart().toLocalTime();
        if (start.isBefore(LocalTime.of(12, 0))) {
            return "D"; // Day
        } else if (start.isBefore(LocalTime.of(20, 0))) {
            return "E"; // Evening
        } else {
            return "N"; // Night
        }
    }

    /**
     * 시간대 슬롯 결정 (위치별 뷰용)
     */
    private static String determineTimeSlot(Shift shift) {
        String code = extractShiftCode(shift);
        return switch (code) {
            case "D" -> "08-16";
            case "E" -> "16-24";
            case "N" -> "00-08";
            default -> "UNKNOWN";
        };
    }

    /**
     * 셀 내용 포맷팅 (간결형)
     */
    private static String formatShiftCell(List<Shift> shifts) {
        if (shifts == null || shifts.isEmpty()) {
            return "OFF";
        }

        // 시작 시간순 정렬
        shifts.sort(Comparator.comparing(Shift::getStart));

        return shifts.stream()
                .map(shift -> {
                    String code = extractShiftCode(shift);
                    String time = String.format("%s-%s",
                            shift.getStart().toLocalTime().truncatedTo(ChronoUnit.HOURS),
                            shift.getEnd().toLocalTime().truncatedTo(ChronoUnit.HOURS));
                    return String.format("%s %s %s", code, time, shift.getLocation());
                })
                .collect(Collectors.joining("<br>"));
    }
}
