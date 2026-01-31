package org.acme.converter;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.acme.api.dto.PlanningRequest;
import org.acme.model.Shift;

/**
 * Shift 객체 생성을 담당하는 팩토리 클래스.
 */
public class ShiftFactory {

    /**
     * 기본 정보로 Shift 객체를 생성합니다.
     *
     * @param date 기준 날짜
     * @param info 시프트 정보
     * @param shiftStartDayOffsets 시프트별 일 오프셋 맵
     * @param defaultLocation 기본 위치
     * @param shiftIdGenerator ID 생성기
     * @return 생성된 Shift 객체
     */
    public static Shift createShift(
            LocalDateTime date,
            PlanningRequest.ShiftInfo info,
            Map<String, Integer> shiftStartDayOffsets,
            String defaultLocation,
            AtomicLong shiftIdGenerator) {
        int dayOffset = shiftStartDayOffsets.getOrDefault(info.id(), 0);

        LocalTime startTime = info.startTime();
        LocalTime endTime = info.endTime();

        LocalDateTime startDateTime = LocalDateTime.of(date.toLocalDate().plusDays(dayOffset), startTime);
        LocalDateTime endDateTime = LocalDateTime.of(date.toLocalDate().plusDays(dayOffset), endTime);

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

    /**
     * 날짜 오프셋 맵을 계산합니다.
     * Night shift가 다음 날에 시작하는 경우를 처리합니다.
     *
     * @param shifts 시프트 정보 리스트
     * @return 시프트 ID별 일 오프셋 맵
     */
    public static Map<String, Integer> calculateShiftOffsets(java.util.List<PlanningRequest.ShiftInfo> shifts) {
        java.util.Map<String, Integer> offsets = new java.util.HashMap<>();
        // Logic: Iterate through shifts in order. If start time resets (goes back),
        // increment day offset.
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
}
