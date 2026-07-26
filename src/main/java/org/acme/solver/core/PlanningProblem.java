package org.acme.solver.core;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 외부 모델과 탐색 구현에 독립적인 불변 계획 문제입니다.
 *
 * <p>입력 encounter order를 내부 정수 index로 고정하고 외부 ID 역매핑을 보존합니다.
 * mutable/effective-pinned/published/historic shift index는 생성 시 한 번 계산합니다.</p>
 */
public final class PlanningProblem {

    public enum AvailabilityKind {
        DESIRED,
        UNDESIRED
    }

    public record ScheduleWindow(
            String tenantId,
            String name,
            Integer publishLength,
            Integer draftLength,
            LocalDate firstDraftDate,
            LocalDate lastHistoricDate) {

        public ScheduleWindow {
            Objects.requireNonNull(firstDraftDate, "firstDraftDate");
            Objects.requireNonNull(lastHistoricDate, "lastHistoricDate");
        }

        public LocalDate firstPublishedDate() {
            return lastHistoricDate.plusDays(1);
        }

        public boolean isHistoric(LocalDateTime start) {
            return start.isBefore(firstPublishedDate().atTime(LocalTime.MIDNIGHT));
        }

        public boolean isDraft(LocalDateTime start) {
            return !start.isBefore(firstDraftDate.atTime(LocalTime.MIDNIGHT));
        }

        public boolean isPublished(LocalDateTime start) {
            return !isHistoric(start) && !isDraft(start);
        }
    }

    public record EmployeeData(
            String externalId,
            String name,
            Set<String> skillSet,
            Set<String> availableShiftCodes,
            int yearlyNightWorkCount,
            int yearlyHolidayWorkCount,
            int yearlyOffRequestCount,
            int offRequestPenaltyWeight,
            String preceptorExternalId) {

        public EmployeeData {
            Objects.requireNonNull(externalId, "externalId");
            skillSet = immutableSet(skillSet);
            availableShiftCodes = immutableSet(availableShiftCodes);
        }
    }

    public record ShiftData(
            long planningId,
            String externalId,
            String shiftCode,
            LocalDateTime start,
            LocalDateTime end,
            LocalDate logicalDate,
            String location,
            String requiredSkill,
            boolean explicitlyPinned,
            int initialEmployeeIndex,
            int nightBurdenScore,
            int holidayBurdenScore) {

        public ShiftData {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(logicalDate, "logicalDate");
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException("shift end는 start보다 뒤여야 합니다: " + planningId);
            }
            if (initialEmployeeIndex < -1) {
                throw new IllegalArgumentException("initialEmployeeIndex는 -1 이상이어야 합니다: " + initialEmployeeIndex);
            }
        }
    }

    public record AvailabilityData(
            Long planningId,
            int employeeIndex,
            LocalDate date,
            AvailabilityKind kind) {

        public AvailabilityData {
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(kind, "kind");
        }
    }

    private final ScheduleWindow scheduleWindow;
    private final List<EmployeeData> employees;
    private final List<ShiftData> shifts;
    private final List<AvailabilityData> availabilities;
    private final Map<String, Integer> employeeIndexByExternalId;
    private final Map<Long, Integer> shiftIndexByPlanningId;
    private final Map<String, List<Integer>> shiftIndexesByExternalId;
    private final int[] initialEmployeeIndexByShift;
    private final int[] mutableShiftIndexes;
    private final int[] pinnedShiftIndexes;
    private final int[] publishedShiftIndexes;
    private final int[] historicShiftIndexes;

    public PlanningProblem(
            ScheduleWindow scheduleWindow,
            List<EmployeeData> employees,
            List<ShiftData> shifts,
            List<AvailabilityData> availabilities) {
        this.scheduleWindow = Objects.requireNonNull(scheduleWindow, "scheduleWindow");
        this.employees = List.copyOf(Objects.requireNonNull(employees, "employees"));
        this.shifts = List.copyOf(Objects.requireNonNull(shifts, "shifts"));
        this.availabilities = List.copyOf(Objects.requireNonNull(availabilities, "availabilities"));

        this.employeeIndexByExternalId = indexEmployees(this.employees);
        this.shiftIndexByPlanningId = indexShiftsByPlanningId(this.shifts);
        this.shiftIndexesByExternalId = indexShiftsByExternalId(this.shifts);

        this.initialEmployeeIndexByShift = new int[this.shifts.size()];
        List<Integer> mutable = new ArrayList<>();
        List<Integer> pinned = new ArrayList<>();
        List<Integer> published = new ArrayList<>();
        List<Integer> historic = new ArrayList<>();

        for (int shiftIndex = 0; shiftIndex < this.shifts.size(); shiftIndex++) {
            ShiftData shift = this.shifts.get(shiftIndex);
            int initialEmployeeIndex = shift.initialEmployeeIndex();
            if (initialEmployeeIndex >= this.employees.size()) {
                throw new IllegalArgumentException(
                        "shift의 initialEmployeeIndex가 직원 범위를 벗어났습니다: shiftIndex="
                                + shiftIndex + ", employeeIndex=" + initialEmployeeIndex);
            }
            initialEmployeeIndexByShift[shiftIndex] = initialEmployeeIndex;

            boolean isHistoric = scheduleWindow.isHistoric(shift.start());
            boolean isPublished = scheduleWindow.isPublished(shift.start());
            boolean isMutable = scheduleWindow.isDraft(shift.start()) && !shift.explicitlyPinned();

            if (isHistoric) {
                historic.add(shiftIndex);
            }
            if (isPublished) {
                published.add(shiftIndex);
            }
            if (isMutable) {
                mutable.add(shiftIndex);
            } else {
                pinned.add(shiftIndex);
            }
        }

        for (AvailabilityData availability : this.availabilities) {
            if (availability.employeeIndex() < 0 || availability.employeeIndex() >= this.employees.size()) {
                throw new IllegalArgumentException(
                        "availability employeeIndex가 직원 범위를 벗어났습니다: " + availability.employeeIndex());
            }
        }

        this.mutableShiftIndexes = toIntArray(mutable);
        this.pinnedShiftIndexes = toIntArray(pinned);
        this.publishedShiftIndexes = toIntArray(published);
        this.historicShiftIndexes = toIntArray(historic);
    }

    public ScheduleWindow scheduleWindow() {
        return scheduleWindow;
    }

    public List<EmployeeData> employees() {
        return employees;
    }

    public List<ShiftData> shifts() {
        return shifts;
    }

    public List<AvailabilityData> availabilities() {
        return availabilities;
    }

    public int employeeCount() {
        return employees.size();
    }

    public int shiftCount() {
        return shifts.size();
    }

    public Map<String, Integer> employeeIndexByExternalId() {
        return employeeIndexByExternalId;
    }

    public Map<Long, Integer> shiftIndexByPlanningId() {
        return shiftIndexByPlanningId;
    }

    public Map<String, List<Integer>> shiftIndexesByExternalId() {
        return shiftIndexesByExternalId;
    }

    public int[] initialEmployeeIndexByShift() {
        return Arrays.copyOf(initialEmployeeIndexByShift, initialEmployeeIndexByShift.length);
    }

    public int initialEmployeeIndex(int shiftIndex) {
        return initialEmployeeIndexByShift[shiftIndex];
    }

    public int[] mutableShiftIndexes() {
        return Arrays.copyOf(mutableShiftIndexes, mutableShiftIndexes.length);
    }

    public int[] pinnedShiftIndexes() {
        return Arrays.copyOf(pinnedShiftIndexes, pinnedShiftIndexes.length);
    }

    public int[] publishedShiftIndexes() {
        return Arrays.copyOf(publishedShiftIndexes, publishedShiftIndexes.length);
    }

    public int[] historicShiftIndexes() {
        return Arrays.copyOf(historicShiftIndexes, historicShiftIndexes.length);
    }

    public boolean isMutableShift(int shiftIndex) {
        ShiftData shift = shifts.get(shiftIndex);
        return scheduleWindow.isDraft(shift.start()) && !shift.explicitlyPinned();
    }

    private static Map<String, Integer> indexEmployees(List<EmployeeData> employees) {
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int employeeIndex = 0; employeeIndex < employees.size(); employeeIndex++) {
            String externalId = employees.get(employeeIndex).externalId();
            Integer previous = index.putIfAbsent(externalId, employeeIndex);
            if (previous != null) {
                throw new IllegalArgumentException("중복 employee external ID: " + externalId);
            }
        }
        return Collections.unmodifiableMap(index);
    }

    private static Map<Long, Integer> indexShiftsByPlanningId(List<ShiftData> shifts) {
        Map<Long, Integer> index = new LinkedHashMap<>();
        for (int shiftIndex = 0; shiftIndex < shifts.size(); shiftIndex++) {
            long planningId = shifts.get(shiftIndex).planningId();
            Integer previous = index.putIfAbsent(planningId, shiftIndex);
            if (previous != null) {
                throw new IllegalArgumentException("중복 shift planning ID: " + planningId);
            }
        }
        return Collections.unmodifiableMap(index);
    }

    private static Map<String, List<Integer>> indexShiftsByExternalId(List<ShiftData> shifts) {
        Map<String, List<Integer>> mutableIndex = new LinkedHashMap<>();
        for (int shiftIndex = 0; shiftIndex < shifts.size(); shiftIndex++) {
            String externalId = shifts.get(shiftIndex).externalId();
            if (externalId != null) {
                mutableIndex.computeIfAbsent(externalId, ignored -> new ArrayList<>()).add(shiftIndex);
            }
        }
        Map<String, List<Integer>> immutableIndex = new LinkedHashMap<>();
        mutableIndex.forEach((key, value) -> immutableIndex.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(immutableIndex);
    }

    private static Set<String> immutableSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int index = 0; index < values.size(); index++) {
            result[index] = values.get(index);
        }
        return result;
    }
}
