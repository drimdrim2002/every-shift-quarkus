package org.acme.util;

import jakarta.enterprise.context.ApplicationScoped;
import org.acme.api.dto.PlanningRequest;
import org.acme.model.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class DtoConverter {

    public static EmployeeSchedule toEmployeeSchedule(PlanningRequest request) {
        // 1. Prepare Reference Data
        Map<String, Employee> employeeMap = mapEmployees(request.employees());
        ScheduleState scheduleState = mapScheduleState(request.organization());

        Map<String, PlanningRequest.ShiftInfo> shiftInfoByCode = request.organization().shifts().stream()
                .collect(Collectors.toMap(PlanningRequest.ShiftInfo::code, Function.identity()));
        Map<String, PlanningRequest.ShiftInfo> shiftInfoById = request.organization().shifts().stream()
                .collect(Collectors.toMap(PlanningRequest.ShiftInfo::id, Function.identity()));

        // Calculate Start Day Offsets (e.g. Night shift might start on next day)
        Map<String, Integer> shiftStartDayOffsets = calculateShiftOffsets(request.organization().shifts());

        List<Shift> finalShiftList = new ArrayList<>();
        List<Availability> availabilityList = new ArrayList<>();

        // ID Generators
        AtomicLong shiftIdGenerator = new AtomicLong(0);
        AtomicLong availabilityIdGenerator = new AtomicLong(0);

        // 2. Phase 1: Create Placeholder Shifts from Requirements
        // Returns a lookup structure [Date -> ShiftId -> Queue<Shift>] to match assignments later
        Map<LocalDate, Map<String, Deque<Shift>>> shiftSlotLookup =
                createShiftsFromRequirements(request, shiftInfoByCode, shiftStartDayOffsets, request.organization().name(), finalShiftList, shiftIdGenerator);

        // 3. Phase 2: Apply Historic Assignments (Pinned Shifts)
        // Returns a tracking map of [Date -> Set<EmployeeId>] for gap detection
        Map<LocalDate, Set<String>> historyMap =
                applyHistory(request, employeeMap, shiftInfoById, shiftStartDayOffsets, shiftSlotLookup, request.organization().name(), finalShiftList, shiftIdGenerator);

        // 4. Phase 3: Fill Historic Gaps
        // Ensures all employees have records in the historic period (Context Continuity)
        fillHistoricGaps(scheduleState, employeeMap, shiftInfoByCode, shiftStartDayOffsets, historyMap, request.organization().name(), finalShiftList, shiftIdGenerator);

        // 5. Phase 4: Process Future Requests (Availability or Pinned Shifts)
        applyUndesirable(request, employeeMap, shiftInfoById, shiftStartDayOffsets, shiftSlotLookup, request.organization().name(), finalShiftList, availabilityList, shiftIdGenerator, availabilityIdGenerator);

        // 6. Construct Final Schedule
        EmployeeSchedule schedule = new EmployeeSchedule();
        schedule.setEmployeeList(new ArrayList<>(employeeMap.values()));
        schedule.setScheduleState(scheduleState);
        schedule.setShiftList(finalShiftList);
        schedule.setAvailabilityList(availabilityList);
        return schedule;
    }

    private static Map<String, Integer> calculateShiftOffsets(List<PlanningRequest.ShiftInfo> shifts) {
        Map<String, Integer> offsets = new HashMap<>();
        // Logic: Iterate through shifts in order. If start time resets (goes back), increment day offset.
        // This handles Day(08) -> Evening(16) -> Night(00 of next day)
        LocalTime previousStartTime = LocalTime.MIN;
        int currentDayOffset = 0;

        for (PlanningRequest.ShiftInfo shift : shifts) {
            // Strictly less than: if same time, assume same day grouping (rare)
            if (shift.startTime().isBefore(previousStartTime)) {
                currentDayOffset++;
            }
            offsets.put(shift.id(), currentDayOffset);
            previousStartTime = shift.startTime();
        }
        return offsets;
    }

    // --- Phase 1: Requirements ---
    private static Map<LocalDate, Map<String, Deque<Shift>>> createShiftsFromRequirements(
            PlanningRequest request,
            Map<String, PlanningRequest.ShiftInfo> shiftInfoByCode,
            Map<String, Integer> shiftStartDayOffsets,
            String defaultLocation,
            List<Shift> shiftList,
            AtomicLong shiftIdGenerator
    ) {
        Map<LocalDate, Map<String, Deque<Shift>>> lookup = new HashMap<>();

        if (request.requirements() == null) return lookup;

        for (Map.Entry<String, Map<String, Integer>> dateEntry : request.requirements().entrySet()) {
            LocalDate date = LocalDate.parse(dateEntry.getKey());
            Map<String, Integer> reqs = dateEntry.getValue();

            for (Map.Entry<String, Integer> req : reqs.entrySet()) {
                String shiftCode = req.getKey();
                if ("total".equalsIgnoreCase(shiftCode)) continue;

                PlanningRequest.ShiftInfo info = shiftInfoByCode.get(shiftCode);
                if (info == null) continue;

                int count = req.getValue();
                for (int i = 0; i < count; i++) {
                    Shift shift = createShift(date, info, shiftStartDayOffsets, defaultLocation, shiftIdGenerator);
                    shiftList.add(shift);

                    lookup.computeIfAbsent(date, d -> new HashMap<>())
                            .computeIfAbsent(info.id(), id -> new ArrayDeque<>())
                            .add(shift);
                }
            }
        }
        return lookup;
    }

    // --- Phase 2: History (Pinned Shifts) ---
    private static Map<LocalDate, Set<String>> applyHistory(
            PlanningRequest request,
            Map<String, Employee> employeeMap,
            Map<String, PlanningRequest.ShiftInfo> shiftInfoById,
            Map<String, Integer> shiftStartDayOffsets,
            Map<LocalDate, Map<String, Deque<Shift>>> shiftSlotLookup,
            String defaultLocation,
            List<Shift> shiftList,
            AtomicLong shiftIdGenerator
    ) {
        Map<LocalDate, Set<String>> historyMap = new HashMap<>();

        if (request.history() == null) return historyMap;

        for (PlanningRequest.AssignmentInfo assignment : request.history()) {
            LocalDate date = assignment.date();
            String shiftId = assignment.shiftId();
            String empId = assignment.employeeId();

            // Track that this employee has a record on this date
            historyMap.computeIfAbsent(date, k -> new HashSet<>()).add(empId);

            // History is always pinned and must result in a Shift
            Shift targetShift = findOrCreateShiftForAssignment(date, shiftId, shiftSlotLookup, shiftInfoById, shiftStartDayOffsets, defaultLocation, shiftList, shiftIdGenerator);

            if (targetShift != null) {
                Employee employee = employeeMap.get(empId);
                if (employee != null) {
                    targetShift.setEmployee(employee);
                    targetShift.setPinned(true); // Always pinned for history
                }
            }
        }
        return historyMap;
    }

    // --- Phase 3: Historic Gaps ---
    private static void fillHistoricGaps(
            ScheduleState scheduleState,
            Map<String, Employee> employeeMap,
            Map<String, PlanningRequest.ShiftInfo> shiftInfoByCode,
            Map<String, Integer> shiftStartDayOffsets,
            Map<LocalDate, Set<String>> historyMap,
            String defaultLocation,
            List<Shift> shiftList,
            AtomicLong shiftIdGenerator
    ) {
        LocalDate lastHistoricDate = scheduleState.getLastHistoricDate();
        LocalDate firstDraftDate = scheduleState.getFirstDraftDate();
        // Fixed/Published period ends exactly before the draft starts
        LocalDate fillEnd = (firstDraftDate != null) ? firstDraftDate.minusDays(1) : lastHistoricDate;

        if (fillEnd == null) return;

        LocalDate fillStart = historyMap.keySet().stream()
                .min(LocalDate::compareTo)
                .orElse(fillEnd);

        if (fillStart.isAfter(fillEnd)) return;

        PlanningRequest.ShiftInfo offShiftInfo = shiftInfoByCode.get("O");
        if (offShiftInfo == null) return;

        for (LocalDate date = fillStart; !date.isAfter(fillEnd); date = date.plusDays(1)) {
            Set<String> assignedEmpIds = historyMap.getOrDefault(date, Collections.emptySet());

            for (Employee employee : employeeMap.values()) {
                if (!assignedEmpIds.contains(employee.getId())) {
                    // Gap detected: Create Pinned OFF Shift
                    Shift offShift = createShift(date, offShiftInfo, shiftStartDayOffsets, defaultLocation, shiftIdGenerator);
                    offShift.setEmployee(employee);
                    offShift.setPinned(true);
                    shiftList.add(offShift);
                }
            }
        }
    }

    // --- Phase 4: Future Requests ---
    private static void applyUndesirable(
            PlanningRequest request,
            Map<String, Employee> employeeMap,
            Map<String, PlanningRequest.ShiftInfo> shiftInfoById,
            Map<String, Integer> shiftStartDayOffsets,
            Map<LocalDate, Map<String, Deque<Shift>>> shiftSlotLookup,
            String defaultLocation,
            List<Shift> shiftList,
            List<Availability> availabilityList,
            AtomicLong shiftIdGenerator,
            AtomicLong availabilityIdGenerator
    ) {
        if (request.undesirable() == null) return;

        for (PlanningRequest.AssignmentInfo req : request.undesirable()) {
            LocalDate date = req.date();
            String empId = req.employeeId();

            Employee employee = employeeMap.get(empId);
            if (employee == null) continue;

            // Not locked -> Availability (UNDESIRABLE)
            Availability availability = new Availability(employee, date, AvailabilityType.UNDESIRABLE);
            availability.setId(availabilityIdGenerator.incrementAndGet());
            availabilityList.add(availability);
        }
    }

    // --- Helpers ---

    private static Shift findOrCreateShiftForAssignment(
            LocalDate date,
            String shiftId,
            Map<LocalDate, Map<String, Deque<Shift>>> shiftSlotLookup,
            Map<String, PlanningRequest.ShiftInfo> shiftInfoById,
            Map<String, Integer> shiftStartDayOffsets,
            String defaultLocation,
            List<Shift> shiftList,
            AtomicLong shiftIdGenerator
    ) {
        // Try to find an empty slot from requirements
        Map<String, Deque<Shift>> dateShifts = shiftSlotLookup.get(date);
        if (dateShifts != null) {
            Deque<Shift> deque = dateShifts.get(shiftId);
            if (deque != null && !deque.isEmpty()) {
                return deque.poll();
            }
        }

        // If no slot available, create a new one (Overflow)
        PlanningRequest.ShiftInfo info = shiftInfoById.get(shiftId);
        if (info != null) {
            Shift newShift = createShift(date, info, shiftStartDayOffsets, defaultLocation, shiftIdGenerator);
            shiftList.add(newShift);
            return newShift;
        }
        return null;
    }

    private static Shift createShift(
            LocalDate date,
            PlanningRequest.ShiftInfo info,
            Map<String, Integer> shiftStartDayOffsets,
            String defaultLocation,
            AtomicLong shiftIdGenerator
    ) {
        int dayOffset = shiftStartDayOffsets.getOrDefault(info.id(), 0);

        LocalTime startTime = info.startTime();
        LocalTime endTime = info.endTime();

        LocalDateTime startDateTime = LocalDateTime.of(date.plusDays(dayOffset), startTime);
        LocalDateTime endDateTime = LocalDateTime.of(date.plusDays(dayOffset), endTime);

        // If end time is before start time (e.g. 16:00 to 00:00, or 22:00 to 06:00)
        // It implies crossing midnight relative to start
        if (!endTime.isAfter(startTime)) {
            endDateTime = endDateTime.plusDays(1);
        }

        String requiredSkill = "ALL"; // Placeholder
        return new Shift(shiftIdGenerator.incrementAndGet(),
                info.id(),
                startDateTime, endDateTime, defaultLocation, requiredSkill, null);
    }

    private static Map<String, Employee> mapEmployees(List<PlanningRequest.EmployeeInfo> employees) {
        Map<String, Employee> map = new HashMap<>();
        for (PlanningRequest.EmployeeInfo e : employees) {
            Set<String> skills = new HashSet<>(e.skillSet());
            skills.add("ALL");
            map.put(e.employeeId(), new Employee(e.employeeId(), e.name(), e.availableShifts(), skills));
        }
        return map;
    }

    private static ScheduleState mapScheduleState(PlanningRequest.OrganizationInfo info) {
        ScheduleState state = new ScheduleState();
        state.setTenantId(info.id());
        state.setName(info.name());
        state.setDraftLength(info.draftLength());
        state.setLastHistoricDate(info.lastHistoricalDate());
        state.setFirstDraftDate(info.firstDraftDate());
        state.setPublishLength(info.publishLength());
        return state;
    }
}