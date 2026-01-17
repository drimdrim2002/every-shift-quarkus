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

        List<Shift> finalShiftList = new ArrayList<>();
        List<Availability> availabilityList = new ArrayList<>();

        // ID Generators
        AtomicLong shiftIdGenerator = new AtomicLong(0);
        AtomicLong availabilityIdGenerator = new AtomicLong(0);

        // 2. Phase 1: Create Placeholder Shifts from Requirements
        // Returns a lookup structure [Date -> ShiftId -> Queue<Shift>] to match assignments later
        Map<LocalDate, Map<String, Deque<Shift>>> shiftSlotLookup = 
            createShiftsFromRequirements(request, shiftInfoByCode, request.organization().name(), finalShiftList, shiftIdGenerator);

        // 3. Phase 2: Apply Historic Assignments (Pinned Shifts)
        // Returns a tracking map of [Date -> Set<EmployeeId>] for gap detection
        Map<LocalDate, Set<String>> historyMap = 
            applyHistory(request, employeeMap, shiftInfoById, shiftSlotLookup, request.organization().name(), finalShiftList, shiftIdGenerator);

        // 4. Phase 3: Fill Historic Gaps
        // Ensures all employees have records in the historic period (Context Continuity)
        fillHistoricGaps(scheduleState, employeeMap, shiftInfoByCode, historyMap, request.organization().name(), finalShiftList, shiftIdGenerator);

        // 5. Phase 4: Process Future Requests (Availability or Pinned Shifts)
        applyRequests(request, employeeMap, shiftInfoById, shiftSlotLookup, request.organization().name(), finalShiftList, availabilityList, shiftIdGenerator, availabilityIdGenerator);

        // 6. Construct Final Schedule
        EmployeeSchedule schedule = new EmployeeSchedule();
        schedule.setEmployeeList(new ArrayList<>(employeeMap.values()));
        schedule.setScheduleState(scheduleState);
        schedule.setShiftList(finalShiftList);
        schedule.setAvailabilityList(availabilityList);
        return schedule;
    }

    // --- Phase 1: Requirements ---
    private static Map<LocalDate, Map<String, Deque<Shift>>> createShiftsFromRequirements(
            PlanningRequest request,
            Map<String, PlanningRequest.ShiftInfo> shiftInfoByCode,
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
                    Shift shift = createShift(date, info, defaultLocation, shiftIdGenerator);
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
            Shift targetShift = findOrCreateShiftForAssignment(date, shiftId, shiftSlotLookup, shiftInfoById, defaultLocation, shiftList, shiftIdGenerator);

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
                    Shift offShift = createShift(date, offShiftInfo, defaultLocation, shiftIdGenerator);
                    offShift.setEmployee(employee);
                    offShift.setPinned(true);
                    shiftList.add(offShift);
                }
            }
        }
    }

    // --- Phase 4: Future Requests ---
    private static void applyRequests(
            PlanningRequest request,
            Map<String, Employee> employeeMap,
            Map<String, PlanningRequest.ShiftInfo> shiftInfoById,
            Map<LocalDate, Map<String, Deque<Shift>>> shiftSlotLookup,
            String defaultLocation,
            List<Shift> shiftList,
            List<Availability> availabilityList,
            AtomicLong shiftIdGenerator,
            AtomicLong availabilityIdGenerator
    ) {
        if (request.requests() == null) return;

        for (PlanningRequest.AssignmentInfo req : request.requests()) {
            LocalDate date = req.date();
            String shiftId = req.shiftId();
            String empId = req.employeeId();
            boolean isLocked = req.isLocked();

            Employee employee = employeeMap.get(empId);
            if (employee == null) continue;

            PlanningRequest.ShiftInfo info = shiftInfoById.get(shiftId);
            if (info == null) continue;

            if (isLocked) {
                // Locked request -> Pinned Shift
                Shift targetShift = findOrCreateShiftForAssignment(date, shiftId, shiftSlotLookup, shiftInfoById, defaultLocation, shiftList, shiftIdGenerator);
                if (targetShift != null) {
                    targetShift.setEmployee(employee);
                    targetShift.setPinned(true);
                }
            } else {
                // Not locked -> Availability
                Availability availability = getAvailability(info, employee, date, availabilityIdGenerator);
                availabilityList.add(availability);
            }
        }
    }

    private static Availability getAvailability(PlanningRequest.ShiftInfo info, Employee employee, LocalDate date, AtomicLong availabilityIdGenerator) {
        boolean isOff =
                "O".equalsIgnoreCase(info.code()) || "Off".equalsIgnoreCase(info.name()) || "H".equalsIgnoreCase(info.name());

        AvailabilityType type = isOff ? AvailabilityType.UNDESIRED : AvailabilityType.DESIRED;
        Availability availability = new Availability(employee, date, type);
        availability.setId(availabilityIdGenerator.incrementAndGet());
        return availability;
    }

    // --- Helpers ---

    private static Shift findOrCreateShiftForAssignment(
            LocalDate date,
            String shiftId,
            Map<LocalDate, Map<String, Deque<Shift>>> shiftSlotLookup,
            Map<String, PlanningRequest.ShiftInfo> shiftInfoById,
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
            Shift newShift = createShift(date, info, defaultLocation, shiftIdGenerator);
            shiftList.add(newShift);
            return newShift;
        }
        return null;
    }

    private static Shift createShift(LocalDate date, PlanningRequest.ShiftInfo info, String defaultLocation, AtomicLong shiftIdGenerator) {
        LocalTime startTime = info.startTime();
        LocalTime endTime = info.endTime();
        LocalDateTime startDateTime = LocalDateTime.of(date, startTime);
        LocalDateTime endDateTime = LocalDateTime.of(date, endTime);

        if (endTime.isBefore(startTime)) {
            endDateTime = endDateTime.plusDays(1);
        }

        String requiredSkill = "ALL"; // Placeholder
        return new Shift(shiftIdGenerator.incrementAndGet(), startDateTime, endDateTime, defaultLocation, requiredSkill, null);
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
