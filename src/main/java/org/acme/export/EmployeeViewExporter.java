package org.acme.export;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.acme.model.Employee;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;

/**
 * 직원별 근무 현황 뷰를 생성하는 Exporter.
 */
public class EmployeeViewExporter {

    /**
     * 직원별 근무 현황 섹션을 생성합니다.
     *
     * @param schedule 스케줄
     * @return Markdown 형식 문자열
     */
    public String build(EmployeeSchedule schedule) {
        StringBuilder sb = new StringBuilder();
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
        return sb.toString();
    }

    /**
     * 직원별 날짜별 Shift 맵 생성
     */
    private Map<String, Map<LocalDate, List<Shift>>> buildEmployeeShiftMap(EmployeeSchedule schedule) {
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
                        .computeIfAbsent(date, d -> new java.util.ArrayList<>())
                        .add(shift);
            }
        }

        return employeeShiftMap;
    }

    /**
     * 셀 내용 포맷팅 (간결형)
     */
    private String formatShiftCell(List<Shift> shifts) {
        if (shifts == null || shifts.isEmpty()) {
            return "OFF";
        }

        // 시작 시간순 정렬
        shifts.sort(Comparator.comparing(Shift::getStart));

        return shifts.stream()
                .map(shift -> {
                    String code = ShiftCodeExtractor.extract(shift);
                    String time = String.format("%s-%s",
                            shift.getStart().toLocalTime().truncatedTo(java.time.temporal.ChronoUnit.HOURS),
                            shift.getEnd().toLocalTime().truncatedTo(java.time.temporal.ChronoUnit.HOURS));
                    return String.format("%s %s %s", code, time, shift.getLocation());
                })
                .collect(Collectors.joining("<br>"));
    }
}
