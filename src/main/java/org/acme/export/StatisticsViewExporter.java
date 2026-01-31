package org.acme.export;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.acme.model.Employee;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;

/**
 * 요약 통계 뷰를 생성하는 Exporter.
 */
public class StatisticsViewExporter {

    /**
     * 요약 통계 섹션을 생성합니다.
     *
     * @param schedule 스케줄
     * @return Markdown 형식 문자열
     */
    public String build(EmployeeSchedule schedule) {
        StringBuilder sb = new StringBuilder();
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
                            String code = ShiftCodeExtractor.extract(shift);
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
}
