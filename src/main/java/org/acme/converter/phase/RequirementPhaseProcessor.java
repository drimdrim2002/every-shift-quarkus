package org.acme.converter.phase;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
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
 * Phase 1: Requirements에서 시프트 슬롯을 생성합니다.
 */
public class RequirementPhaseProcessor implements PhaseProcessor {

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

        Map<LocalDate, Map<String, Deque<Shift>>> lookup = new HashMap<>();

        if (request.requirements() == null) {
            return new PhaseResult(lookup);
        }

        // List<RequirementInfo> 구조
        for (PlanningRequest.RequirementInfo req : request.requirements()) {
            LocalDate date = request.organization().firstDraftDate().plusDays(req.dayIndex());
            String shiftId = req.shiftId();
            int count = req.employeeCount();

            PlanningRequest.ShiftInfo info = shiftInfoByCode.get(shiftId);
            if (info == null) {
                continue;
            }

            for (int i = 0; i < count; i++) {
                Shift shift = createShift(date, info, shiftStartDayOffsets, defaultLocation, shiftIdGenerator);
                shiftList.add(shift);

                lookup.computeIfAbsent(date, d -> new HashMap<>())
                        .computeIfAbsent(info.id(), id -> new ArrayDeque<>())
                        .add(shift);
            }
        }

        return new PhaseResult(lookup);
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
        // It implies crossing midnight relative to start
        if (!endTime.isAfter(startTime)) {
            endDateTime = endDateTime.plusDays(1);
        }

        String requiredSkill = "ALL";
        return new Shift(shiftIdGenerator.incrementAndGet(),
                info.id(),
                startDateTime, endDateTime, defaultLocation, requiredSkill, null);
    }
}
