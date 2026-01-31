package org.acme.converter;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.phase.HistoricGapPhaseProcessor;
import org.acme.converter.phase.HistoryPhaseProcessor;
import org.acme.converter.phase.PhaseProcessor;
import org.acme.converter.phase.RequirementPhaseProcessor;
import org.acme.converter.phase.UndesirablePhaseProcessor;
import org.acme.model.Availability;
import org.acme.model.Employee;
import org.acme.model.EmployeeSchedule;
import org.acme.model.ScheduleState;
import org.acme.model.Shift;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * PlanningRequest를 EmployeeSchedule로 변환하는 빌더 클래스.
 * DtoConverter를 대체하며, 4단계 PhaseProcessor 패턴을 사용합니다.
 */
@ApplicationScoped
public class EmployeeScheduleBuilder {

    private final RequirementPhaseProcessor requirementPhaseProcessor = new RequirementPhaseProcessor();
    private final HistoryPhaseProcessor historyPhaseProcessor = new HistoryPhaseProcessor();
    private final HistoricGapPhaseProcessor historicGapPhaseProcessor = new HistoricGapPhaseProcessor();
    private final UndesirablePhaseProcessor undesirablePhaseProcessor = new UndesirablePhaseProcessor();

    /**
     * PlanningRequest를 EmployeeSchedule로 변환합니다.
     *
     * @param request 계획 요청
     * @return 변환된 직원 스케줄
     */
    public EmployeeSchedule build(PlanningRequest request) {
        // 1. Prepare Reference Data
        Map<String, Employee> employeeMap = mapEmployees(request.employees());
        ScheduleState scheduleState = mapScheduleState(request.organization());

        Map<String, PlanningRequest.ShiftInfo> shiftInfoByCode = request.organization().shifts().stream()
                .collect(Collectors.toMap(PlanningRequest.ShiftInfo::code, Function.identity()));
        Map<String, PlanningRequest.ShiftInfo> shiftInfoById = request.organization().shifts().stream()
                .collect(Collectors.toMap(PlanningRequest.ShiftInfo::id, Function.identity()));

        // Calculate Start Day Offsets (e.g. Night shift might start on next day)
        Map<String, Integer> shiftStartDayOffsets = ShiftFactory.calculateShiftOffsets(request.organization().shifts());

        List<Shift> finalShiftList = new ArrayList<>();
        List<Availability> availabilityList = new ArrayList<>();

        // ID Generators
        AtomicLong shiftIdGenerator = new AtomicLong(0);
        AtomicLong availabilityIdGenerator = new AtomicLong(0);

        // 2. Phase 1: Create Placeholder Shifts from Requirements
        PhaseProcessor.PhaseResult phase1Result = requirementPhaseProcessor.process(
                request, employeeMap, shiftInfoByCode, shiftInfoById, shiftStartDayOffsets,
                scheduleState, request.organization().name(), finalShiftList, availabilityList,
                shiftIdGenerator, availabilityIdGenerator, null);

        // 3. Phase 2: Apply Historic Assignments (Pinned Shifts)
        PhaseProcessor.PhaseResult phase2Result = historyPhaseProcessor.process(
                request, employeeMap, shiftInfoByCode, shiftInfoById, shiftStartDayOffsets,
                scheduleState, request.organization().name(), finalShiftList, availabilityList,
                shiftIdGenerator, availabilityIdGenerator, phase1Result);

        // 4. Phase 3: Fill Historic Gaps
        PhaseProcessor.PhaseResult phase3Result = historicGapPhaseProcessor.process(
                request, employeeMap, shiftInfoByCode, shiftInfoById, shiftStartDayOffsets,
                scheduleState, request.organization().name(), finalShiftList, availabilityList,
                shiftIdGenerator, availabilityIdGenerator, phase2Result);

        // 5. Phase 4: Process Future Requests (Availability)
        PhaseProcessor.PhaseResult phase4Result = undesirablePhaseProcessor.process(
                request, employeeMap, shiftInfoByCode, shiftInfoById, shiftStartDayOffsets,
                scheduleState, request.organization().name(), finalShiftList, availabilityList,
                shiftIdGenerator, availabilityIdGenerator, phase3Result);

        // 6. Construct Final Schedule
        EmployeeSchedule schedule = new EmployeeSchedule();
        schedule.setEmployeeList(new ArrayList<>(employeeMap.values()));
        schedule.setScheduleState(scheduleState);
        schedule.setShiftList(finalShiftList);
        schedule.setAvailabilityList(availabilityList);
        return schedule;
    }

    /**
     * 직원 정보 리스트를 직원 맵으로 변환합니다.
     */
    private Map<String, Employee> mapEmployees(List<PlanningRequest.EmployeeInfo> employees) {
        Map<String, Employee> map = new HashMap<>();
        for (PlanningRequest.EmployeeInfo e : employees) {
            Set<String> skills = new HashSet<>(e.skillSet());
            skills.add("ALL");
            map.put(e.employeeId(), new Employee(e.employeeId(), e.name(), e.availableShifts(), skills));
        }
        return map;
    }

    /**
     * 조직 정보를 ScheduleState로 변환합니다.
     */
    private ScheduleState mapScheduleState(PlanningRequest.OrganizationInfo info) {
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
