package org.acme.export.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 근무표 JSON 내보내기용 DTO.
 */
public class ScheduleJsonDto {

    @JsonProperty("metadata")
    private ScheduleMetadataDto metadata;

    @JsonProperty("employees")
    private List<EmployeeShiftsDto> employees;

    @JsonProperty("statistics")
    private List<EmployeeStatisticsDto> statistics;

    public ScheduleJsonDto() {
    }

    public ScheduleJsonDto(ScheduleMetadataDto metadata, List<EmployeeShiftsDto> employees,
            List<EmployeeStatisticsDto> statistics) {
        this.metadata = metadata;
        this.employees = employees;
        this.statistics = statistics;
    }

    public ScheduleMetadataDto getMetadata() {
        return metadata;
    }

    public void setMetadata(ScheduleMetadataDto metadata) {
        this.metadata = metadata;
    }

    public List<EmployeeShiftsDto> getEmployees() {
        return employees;
    }

    public void setEmployees(List<EmployeeShiftsDto> employees) {
        this.employees = employees;
    }

    public List<EmployeeStatisticsDto> getStatistics() {
        return statistics;
    }

    public void setStatistics(List<EmployeeStatisticsDto> statistics) {
        this.statistics = statistics;
    }

    /**
     * 메타데이터 DTO.
     */
    public static class ScheduleMetadataDto {

        @JsonProperty("score")
        private String score;

        @JsonProperty("generatedAt")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime generatedAt;

        @JsonProperty("totalEmployees")
        private int totalEmployees;

        @JsonProperty("totalShifts")
        private int totalShifts;

        @JsonProperty("draftRange")
        private DraftRangeDto draftRange;

        public ScheduleMetadataDto() {
        }

        public ScheduleMetadataDto(String score, LocalDateTime generatedAt, int totalEmployees, int totalShifts,
                DraftRangeDto draftRange) {
            this.score = score;
            this.generatedAt = generatedAt;
            this.totalEmployees = totalEmployees;
            this.totalShifts = totalShifts;
            this.draftRange = draftRange;
        }

        public String getScore() {
            return score;
        }

        public void setScore(String score) {
            this.score = score;
        }

        public LocalDateTime getGeneratedAt() {
            return generatedAt;
        }

        public void setGeneratedAt(LocalDateTime generatedAt) {
            this.generatedAt = generatedAt;
        }

        public int getTotalEmployees() {
            return totalEmployees;
        }

        public void setTotalEmployees(int totalEmployees) {
            this.totalEmployees = totalEmployees;
        }

        public int getTotalShifts() {
            return totalShifts;
        }

        public void setTotalShifts(int totalShifts) {
            this.totalShifts = totalShifts;
        }

        public DraftRangeDto getDraftRange() {
            return draftRange;
        }

        public void setDraftRange(DraftRangeDto draftRange) {
            this.draftRange = draftRange;
        }
    }

    /**
     * Draft 범위 DTO.
     */
    public static class DraftRangeDto {

        @JsonProperty("start")
        @JsonFormat(pattern = "yyyy-MM-dd")
        private LocalDate start;

        @JsonProperty("end")
        @JsonFormat(pattern = "yyyy-MM-dd")
        private LocalDate end;

        public DraftRangeDto() {
        }

        public DraftRangeDto(LocalDate start, LocalDate end) {
            this.start = start;
            this.end = end;
        }

        public LocalDate getStart() {
            return start;
        }

        public void setStart(LocalDate start) {
            this.start = start;
        }

        public LocalDate getEnd() {
            return end;
        }

        public void setEnd(LocalDate end) {
            this.end = end;
        }
    }

    /**
     * 직원별 시프트 DTO.
     */
    public static class EmployeeShiftsDto {

        @JsonProperty("id")
        private String id;

        @JsonProperty("name")
        private String name;

        @JsonProperty("shifts")
        private List<ShiftDetailDto> shifts;

        public EmployeeShiftsDto() {
        }

        public EmployeeShiftsDto(String id, String name, List<ShiftDetailDto> shifts) {
            this.id = id;
            this.name = name;
            this.shifts = shifts;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<ShiftDetailDto> getShifts() {
            return shifts;
        }

        public void setShifts(List<ShiftDetailDto> shifts) {
            this.shifts = shifts;
        }
    }

    /**
     * 시프트 상세 DTO.
     */
    public static class ShiftDetailDto {

        /**
         * 논리 근무일.
         * Night(N)는 실제 시작일 - 1일, Day/Evening은 실제 시작일과 동일합니다.
         */
        @JsonProperty("date")
        @JsonFormat(pattern = "yyyy-MM-dd")
        private LocalDate date;

        @JsonProperty("code")
        private String code;

        /**
         * 실제 근무 시작 일시 (yyyy-MM-dd HH:mm).
         */
        @JsonProperty("startTime")
        private String startTime;

        /**
         * 실제 근무 종료 일시 (yyyy-MM-dd HH:mm).
         */
        @JsonProperty("endTime")
        private String endTime;

        @JsonProperty("location")
        private String location;

        public ShiftDetailDto() {
        }

        public ShiftDetailDto(LocalDate date, String code, String startTime, String endTime, String location) {
            this.date = date;
            this.code = code;
            this.startTime = startTime;
            this.endTime = endTime;
            this.location = location;
        }

        public LocalDate getDate() {
            return date;
        }

        public void setDate(LocalDate date) {
            this.date = date;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getStartTime() {
            return startTime;
        }

        public void setStartTime(String startTime) {
            this.startTime = startTime;
        }

        public String getEndTime() {
            return endTime;
        }

        public void setEndTime(String endTime) {
            this.endTime = endTime;
        }

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }
    }

    /**
     * 직원별 통계 DTO.
     */
    public static class EmployeeStatisticsDto {

        @JsonProperty("employeeId")
        private String employeeId;

        @JsonProperty("employeeName")
        private String employeeName;

        @JsonProperty("totalWorkDays")
        private int totalWorkDays;

        @JsonProperty("dayShifts")
        private int dayShifts;

        @JsonProperty("eveningShifts")
        private int eveningShifts;

        @JsonProperty("nightShifts")
        private int nightShifts;

        public EmployeeStatisticsDto() {
        }

        public EmployeeStatisticsDto(String employeeId, String employeeName, int totalWorkDays, int dayShifts,
                int eveningShifts, int nightShifts) {
            this.employeeId = employeeId;
            this.employeeName = employeeName;
            this.totalWorkDays = totalWorkDays;
            this.dayShifts = dayShifts;
            this.eveningShifts = eveningShifts;
            this.nightShifts = nightShifts;
        }

        public String getEmployeeId() {
            return employeeId;
        }

        public void setEmployeeId(String employeeId) {
            this.employeeId = employeeId;
        }

        public String getEmployeeName() {
            return employeeName;
        }

        public void setEmployeeName(String employeeName) {
            this.employeeName = employeeName;
        }

        public int getTotalWorkDays() {
            return totalWorkDays;
        }

        public void setTotalWorkDays(int totalWorkDays) {
            this.totalWorkDays = totalWorkDays;
        }

        public int getDayShifts() {
            return dayShifts;
        }

        public void setDayShifts(int dayShifts) {
            this.dayShifts = dayShifts;
        }

        public int getEveningShifts() {
            return eveningShifts;
        }

        public void setEveningShifts(int eveningShifts) {
            this.eveningShifts = eveningShifts;
        }

        public int getNightShifts() {
            return nightShifts;
        }

        public void setNightShifts(int nightShifts) {
            this.nightShifts = nightShifts;
        }
    }
}
