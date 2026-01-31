package org.acme.export;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.acme.model.Employee;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;

/**
 * 타임라인 뷰를 생성하는 Exporter.
 */
public class TimelineViewExporter {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd");

    /**
     * 타임라인 뷰 섹션을 생성합니다.
     *
     * @param schedule 스케줄
     * @return Markdown 형식 문자열
     */
    public String build(EmployeeSchedule schedule) {
        StringBuilder sb = new StringBuilder();
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
                        String code = ShiftCodeExtractor.extract(shift);
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
