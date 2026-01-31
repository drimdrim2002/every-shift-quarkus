package org.acme.converter.phase;

import java.time.LocalDate;
import java.util.Collections;
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
 * Phase 3: 과거 기록의 빈 간격을 OFF 시프트로 채웁니다.
 * 모든 직원이 과거 기록 동안 기록을 가지도록 보장합니다 (Context Continuity).
 */
public class HistoricGapPhaseProcessor implements PhaseProcessor {

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

        LocalDate lastHistoricDate = scheduleState.getLastHistoricDate();
        LocalDate firstDraftDate = scheduleState.getFirstDraftDate();
        // Fixed/Published period ends exactly before the draft starts
        LocalDate fillEnd = (firstDraftDate != null) ? firstDraftDate.minusDays(1) : lastHistoricDate;

        if (fillEnd == null) {
            return previousPhaseResult;
        }

        Map<LocalDate, Set<String>> historyMap = previousPhaseResult.getHistoryMap();
        if (historyMap == null) {
            historyMap = new HashMap<>();
        }

        LocalDate fillStart = historyMap.keySet().stream()
                .min(LocalDate::compareTo)
                .orElse(fillEnd);

        if (fillStart.isAfter(fillEnd)) {
            return previousPhaseResult;
        }

        PlanningRequest.ShiftInfo offShiftInfo = shiftInfoByCode.get("O");
        if (offShiftInfo == null) {
            return previousPhaseResult;
        }

        for (LocalDate date = fillStart; !date.isAfter(fillEnd); date = date.plusDays(1)) {
            Set<String> assignedEmpIds = historyMap.getOrDefault(date, Collections.emptySet());

            for (Employee employee : employeeMap.values()) {
                if (!assignedEmpIds.contains(employee.getId())) {
                    // Gap detected: Create Pinned OFF Shift
                    Shift offShift = createShift(date, offShiftInfo, shiftStartDayOffsets, defaultLocation,
                            shiftIdGenerator);
                    offShift.setEmployee(employee);
                    offShift.setPinned(true);
                    shiftList.add(offShift);
                }
            }
        }

        return previousPhaseResult;
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
        return new Shift(shiftIdGenerator.incrementAndGet(),
                info.id(),
                startDateTime, endDateTime, defaultLocation, requiredSkill, null);
    }
}
