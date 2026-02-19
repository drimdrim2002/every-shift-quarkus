package org.acme.converter.phase;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.acme.api.dto.PlanningRequest;
import org.acme.model.Availability;
import org.acme.model.AvailabilityType;
import org.acme.model.Employee;
import org.acme.model.ScheduleState;
import org.acme.model.Shift;

/**
 * Phase 4: 미래 요청(불가능한 날짜)을 가용성으로 처리합니다.
 */
public class UndesirablePhaseProcessor implements PhaseProcessor {

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

        if (request.undesirable() == null) {
            return previousPhaseResult;
        }

        Set<String> processedUndesiredKeys = new HashSet<>();
        for (PlanningRequest.AssignmentInfo req : request.undesirable()) {
            LocalDate date = req.date();
            String empId = req.employeeId();
            if (date == null || empId == null || empId.isBlank()) {
                continue;
            }

            Employee employee = employeeMap.get(empId);
            if (employee == null) {
                continue;
            }

            String dedupeKey = empId + "|" + date;
            if (!processedUndesiredKeys.add(dedupeKey)) {
                continue;
            }

            // Not locked -> Availability (UNDESIRED)
            Availability availability = new Availability(employee, date, AvailabilityType.UNDESIRED);
            availability.setId(availabilityIdGenerator.incrementAndGet());
            availabilityList.add(availability);
        }

        return previousPhaseResult;
    }
}
