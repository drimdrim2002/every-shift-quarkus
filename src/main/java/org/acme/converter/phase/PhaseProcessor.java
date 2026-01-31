package org.acme.converter.phase;

import java.time.LocalDate;
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
 * 변환 단계(Phase)를 처리하는 프로세서 인터페이스.
 */
public interface PhaseProcessor {

    /**
     * 프로세서의 결과를 나타내는 클래스.
     */
    class PhaseResult {
        private final Map<LocalDate, Map<String, java.util.Deque<Shift>>> shiftSlotLookup;
        private final Map<LocalDate, Set<String>> historyMap;

        public PhaseResult(Map<LocalDate, Map<String, java.util.Deque<Shift>>> shiftSlotLookup) {
            this.shiftSlotLookup = shiftSlotLookup;
            this.historyMap = null;
        }

        public PhaseResult(Map<LocalDate, Map<String, java.util.Deque<Shift>>> shiftSlotLookup,
                          Map<LocalDate, Set<String>> historyMap) {
            this.shiftSlotLookup = shiftSlotLookup;
            this.historyMap = historyMap;
        }

        public Map<LocalDate, Map<String, java.util.Deque<Shift>>> getShiftSlotLookup() {
            return shiftSlotLookup;
        }

        public Map<LocalDate, Set<String>> getHistoryMap() {
            return historyMap;
        }
    }

    /**
     * 이 Phase를 처리합니다.
     *
     * @param request 요청 데이터
     * @param employeeMap 직원 맵
     * @param shiftInfoByCode 코드별 시프트 정보
     * @param shiftInfoById ID별 시프트 정보
     * @param shiftStartDayOffsets 시프트 시작일 오프셋
     * @param scheduleState 스케줄 상태
     * @param defaultLocation 기본 위치
     * @param shiftList 시프트 리스트 (추가됨)
     * @param availabilityList 가용성 리스트 (추가됨)
     * @param shiftIdGenerator 시프트 ID 생성기
     * @param availabilityIdGenerator 가용성 ID 생성기
     * @param previousPhaseResult 이전 Phase 결과
     * @return 이 Phase의 결과
     */
    PhaseResult process(
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
            PhaseResult previousPhaseResult
    );
}
