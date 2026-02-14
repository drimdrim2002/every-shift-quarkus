package org.acme.export;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.acme.export.dto.ScheduleJsonDto;
import org.acme.export.dto.ScheduleJsonDto.DraftRangeDto;
import org.acme.export.dto.ScheduleJsonDto.EmployeeShiftsDto;
import org.acme.export.dto.ScheduleJsonDto.EmployeeStatisticsDto;
import org.acme.export.dto.ScheduleJsonDto.ScheduleMetadataDto;
import org.acme.export.dto.ScheduleJsonDto.ShiftDetailDto;
import org.acme.model.Employee;
import org.acme.model.EmployeeSchedule;
import org.acme.model.ScheduleState;
import org.acme.model.Shift;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 직원 스케줄을 JSON 형식으로 내보내는 Exporter.
 */
@ApplicationScoped
public class JsonScheduleExporter {

    @Inject
    ObjectMapper objectMapper;

    /**
     * 직원 스케줄을 JSON DTO로 변환합니다.
     *
     * @param schedule OptaPlanner 솔루션
     * @return JSON DTO
     */
    public ScheduleJsonDto toJson(EmployeeSchedule schedule) {
        if (schedule == null) {
            return new ScheduleJsonDto();
        }

        // 메타데이터 생성
        ScheduleMetadataDto metadata = buildMetadata(schedule);

        // 직원별 시프트 데이터 생성
        List<EmployeeShiftsDto> employees = buildEmployeeShifts(schedule);

        // 통계 데이터 생성
        List<EmployeeStatisticsDto> statistics = buildStatistics(schedule);

        return new ScheduleJsonDto(metadata, employees, statistics);
    }

    /**
     * 직원 스케줄을 JSON 문자열로 변환합니다.
     *
     * @param schedule OptaPlanner 솔루션
     * @return JSON 형식 문자열
     */
    public String toJsonString(EmployeeSchedule schedule) {
        ScheduleJsonDto dto = toJson(schedule);
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(dto);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize schedule to JSON", e);
        }
    }

    /**
     * 메타데이터를 생성합니다.
     */
    private ScheduleMetadataDto buildMetadata(EmployeeSchedule schedule) {
        String score = schedule.getScore() != null ? schedule.getScore().toString() : "unknown";
        LocalDateTime generatedAt = LocalDateTime.now();
        int totalEmployees = schedule.getEmployeeList().size();
        int totalShifts = schedule.getShiftList().size();

        DraftRangeDto draftRange = resolveDraftRange(schedule);

        return new ScheduleMetadataDto(score, generatedAt, totalEmployees, totalShifts, draftRange);
    }

    /**
     * 직원별 시프트 데이터를 생성합니다.
     */
    private List<EmployeeShiftsDto> buildEmployeeShifts(EmployeeSchedule schedule) {
        LocalDate[] draftRange = resolveDraftRangeAsArray(schedule);

        // 날짜 범위 결정
        LocalDate startInclusive = draftRange != null ? draftRange[0] : null;
        LocalDate endExclusive = draftRange != null ? draftRange[1] : null;

        // 전체 날짜 집합 (대상 범위만, 정렬됨)
        TreeSet<LocalDate> targetDates = schedule.getShiftList().stream()
                .map(shift -> shift.getStart().toLocalDate())
                .filter(date -> startInclusive == null || !date.isBefore(startInclusive))
                .filter(date -> endExclusive == null || !date.isAfter(endExclusive))
                .collect(Collectors.toCollection(TreeSet::new));

        // 직원별 날짜별 Shift 매핑
        Map<String, Map<LocalDate, List<Shift>>> employeeShiftMap = buildEmployeeShiftMap(schedule);

        // 직원 이름순 정렬
        List<Employee> sortedEmployees = schedule.getEmployeeList().stream()
                .sorted(Comparator.comparing(Employee::getName))
                .toList();

        // DTO 변환
        List<EmployeeShiftsDto> result = new ArrayList<>();
        for (Employee employee : sortedEmployees) {
            Map<LocalDate, List<Shift>> dateShiftMap = employeeShiftMap.get(employee.getId());

            List<ShiftDetailDto> shifts = new ArrayList<>();
            for (LocalDate date : targetDates) {
                List<Shift> shiftList = dateShiftMap != null ? dateShiftMap.get(date) : null;
                if (shiftList != null && !shiftList.isEmpty()) {
                    for (Shift shift : shiftList) {
                        shifts.add(toShiftDetailDto(shift));
                    }
                }
            }

            result.add(new EmployeeShiftsDto(employee.getId(), employee.getName(), shifts));
        }

        return result;
    }

    /**
     * 시프트 상세 DTO로 변환합니다.
     */
    private ShiftDetailDto toShiftDetailDto(Shift shift) {
        LocalDate date = shift.getStart().toLocalDate();
        String code = determineShiftCode(shift);
        String startTime = formatTime(getStartTime(code));
        String endTime = formatTime(getEndTime(code));
        String location = shift.getLocation();

        return new ShiftDetailDto(date, code, startTime, endTime, location);
    }

    /**
     * 시프트 시작 시간에 따라 시프트 코드를 결정합니다.
     */
    private String determineShiftCode(Shift shift) {
        LocalTime start = shift.getStart().toLocalTime();
        if (start.isBefore(LocalTime.of(8, 0))) {
            return "N"; // 00:00-08:00
        } else if (start.isBefore(LocalTime.of(16, 0))) {
            return "D"; // 08:00-16:00
        } else {
            return "E"; // 16:00-24:00
        }
    }

    /**
     * 시프트 코드에 따른 시작 시간을 반환합니다.
     */
    private LocalTime getStartTime(String code) {
        return switch (code) {
            case "D" -> LocalTime.of(8, 0);
            case "E" -> LocalTime.of(16, 0);
            case "N" -> LocalTime.of(0, 0);
            default -> LocalTime.MIDNIGHT;
        };
    }

    /**
     * 시프트 코드에 따른 종료 시간을 반환합니다.
     */
    private LocalTime getEndTime(String code) {
        return switch (code) {
            case "D" -> LocalTime.of(16, 0);
            case "E" -> LocalTime.of(0, 0);
            case "N" -> LocalTime.of(8, 0);
            default -> LocalTime.MIDNIGHT;
        };
    }

    /**
     * 시간을 HH:mm 형식으로 포맷팅합니다.
     */
    private String formatTime(LocalTime time) {
        return time.toString();
    }

    /**
     * 통계 데이터를 생성합니다.
     */
    private List<EmployeeStatisticsDto> buildStatistics(EmployeeSchedule schedule) {
        Map<String, Map<LocalDate, List<Shift>>> employeeShiftMap = buildEmployeeShiftMap(schedule);

        // 직원 이름순 정렬
        List<Employee> sortedEmployees = schedule.getEmployeeList().stream()
                .sorted(Comparator.comparing(Employee::getName))
                .toList();

        List<EmployeeStatisticsDto> result = new ArrayList<>();
        for (Employee employee : sortedEmployees) {
            Map<LocalDate, List<Shift>> dateShiftMap = employeeShiftMap.get(employee.getId());

            int totalWorkDays = 0;
            int dayShifts = 0;
            int eveningShifts = 0;
            int nightShifts = 0;

            if (dateShiftMap != null) {
                for (List<Shift> shifts : dateShiftMap.values()) {
                    if (shifts != null && !shifts.isEmpty()) {
                        totalWorkDays++;
                        for (Shift shift : shifts) {
                            String code = determineShiftCode(shift);
                            switch (code) {
                                case "D" -> dayShifts++;
                                case "E" -> eveningShifts++;
                                case "N" -> nightShifts++;
                            }
                        }
                    }
                }
            }

            result.add(new EmployeeStatisticsDto(
                    employee.getId(),
                    employee.getName(),
                    totalWorkDays,
                    dayShifts,
                    eveningShifts,
                    nightShifts));
        }

        return result;
    }

    /**
     * 직원별 날짜별 Shift 맵을 생성합니다.
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
                        .computeIfAbsent(date, d -> new ArrayList<>())
                        .add(shift);
            }
        }

        return employeeShiftMap;
    }

    /**
     * Draft 범위를 계산하여 DTO로 반환합니다.
     */
    private DraftRangeDto resolveDraftRange(EmployeeSchedule schedule) {
        LocalDate[] range = resolveDraftRangeAsArray(schedule);
        if (range == null) {
            return null;
        }
        return new DraftRangeDto(range[0], range[1]);
    }

    /**
     * Draft 날짜 범위를 계산합니다.
     *
     * @return [startInclusive, endExclusive] 또는 null
     */
    private LocalDate[] resolveDraftRangeAsArray(EmployeeSchedule schedule) {
        if (schedule == null) {
            return null;
        }

        ScheduleState scheduleState = schedule.getScheduleState();
        if (scheduleState == null) {
            return null;
        }

        LocalDate startInclusive = scheduleState.getFirstDraftDate();
        Integer draftLength = scheduleState.getDraftLength();
        if (startInclusive == null || draftLength == null || draftLength <= 0) {
            return null;
        }

        LocalDate endExclusive = startInclusive.plusDays(draftLength);
        return new LocalDate[] { startInclusive, endExclusive };
    }
}
