package org.acme.converter.phase;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.acme.api.dto.PlanningRequest;
import org.acme.model.Availability;
import org.acme.model.Employee;
import org.acme.model.ScheduleState;
import org.acme.model.Shift;

/**
 * Phase 2: History(과거 근무 기록)를 적용하여 고정된 시프트를 생성합니다.
 */
public class HistoryPhaseProcessor implements PhaseProcessor {

    @Override
    public PhaseResult process(
            PlanningRequest request,
            Map<String, Employee> employeeMap,
            Map<String, PlanningRequest.ShiftInfo> shiftInfoByCode,
            Map<String, PlanningRequest.ShiftInfo> shiftInfoById,
            Map<String, Integer> shiftStartDayOffsets,
            ScheduleState scheduleState,
            String defaultLocation,
            List<Shift> shiftList,
            List<Availability> availabilityList,
            AtomicLong shiftIdGenerator,
            AtomicLong availabilityIdGenerator,
            PhaseResult previousPhaseResult) {

        Map<LocalDate, Set<String>> historyMap = new HashMap<>();
        Map<LocalDate, Map<String, Deque<Shift>>> shiftSlotLookup = previousPhaseResult.getShiftSlotLookup();

        if (request.history() == null) {
            return new PhaseResult(shiftSlotLookup, historyMap);
        }

        for (PlanningRequest.AssignmentInfo assignment : request.history()) {
            LocalDate date = assignment.date();
            String shiftId = assignment.shiftId();
            String empId = assignment.employeeId();

            // Track that this employee has a record on this date
            historyMap.computeIfAbsent(date, k -> new HashSet<>()).add(empId);

            // History is always pinned and must result in a Shift
            Shift targetShift = findOrCreateShiftForAssignment(date, shiftId, shiftSlotLookup, shiftInfoById,
                    shiftStartDayOffsets, defaultLocation, shiftList, shiftIdGenerator);

            if (targetShift != null) {
                Employee employee = employeeMap.get(empId);
                if (employee != null) {
                    targetShift.setEmployee(employee);
                    targetShift.setPinned(true); // Always pinned for history
                }
            }
        }

        return new PhaseResult(shiftSlotLookup, historyMap);
    }

    private Shift findOrCreateShiftForAssignment(
            LocalDate date,
            String shiftId,
            Map<LocalDate, Map<String, Deque<Shift>>> shiftSlotLookup,
            Map<String, PlanningRequest.ShiftInfo> shiftInfoById,
            Map<String, Integer> shiftStartDayOffsets,
            String defaultLocation,
            List<Shift> shiftList,
            AtomicLong shiftIdGenerator) {

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

    private Shift createShift(
            LocalDate date,
            PlanningRequest.ShiftInfo info,
            Map<String, Integer> shiftStartDayOffsets,
            String defaultLocation,
            AtomicLong shiftIdGenerator) {

        int dayOffset = shiftStartDayOffsets.getOrDefault(info.id(), 0);

        var startTime = info.startTime();
        var endTime = info.endTime();

        var startDateTime = java.time.LocalDateTime.of(date.plusDays(dayOffset), startTime);
        var endDateTime = java.time.LocalDateTime.of(date.plusDays(dayOffset), endTime);

        // If end time is before start time (e.g. 16:00 to 00:00)
        if (!endTime.isAfter(startTime)) {
            endDateTime = endDateTime.plusDays(1);
        }

        String requiredSkill = "ALL";
        Shift shift = new Shift(shiftIdGenerator.incrementAndGet(),
                info.id(),
                startDateTime, endDateTime, defaultLocation, requiredSkill, null);
        shift.setShiftCode(info.code());
        return shift;
    }
}
