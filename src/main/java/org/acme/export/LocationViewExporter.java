package org.acme.export;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;

/**
 * 위치별 근무표 뷰를 생성하는 Exporter.
 */
public class LocationViewExporter {

    /**
     * 위치별 근무표 섹션을 생성합니다.
     *
     * @param schedule 스케줄
     * @return Markdown 형식 문자열
     */
    public String build(EmployeeSchedule schedule) {
        StringBuilder sb = new StringBuilder();
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
                    String timeSlot = ShiftCodeExtractor.determineTimeSlot(shift);

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

        return sb.toString();
    }
}
