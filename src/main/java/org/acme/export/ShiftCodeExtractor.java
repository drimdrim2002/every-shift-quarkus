package org.acme.export;

import java.time.LocalTime;

import org.acme.model.Shift;

/**
 * 시프트 코드 추출 유틸리티.
 * 여러 Exporter에서 공통으로 사용합니다.
 */
public class ShiftCodeExtractor {

    /**
     * ShiftCode를 추출합니다 (D/E/N).
     *
     * @param shift 시프트
     * @return 시프트 코드
     */
    public static String extract(Shift shift) {
        LocalTime start = shift.getStart().toLocalTime();
        if (start.isBefore(LocalTime.of(12, 0))) {
            return "D"; // Day
        } else if (start.isBefore(LocalTime.of(20, 0))) {
            return "E"; // Evening
        } else {
            return "N"; // Night
        }
    }

    /**
     * 시간대 슬롯을 결정합니다 (위치별 뷰용).
     *
     * @param shift 시프트
     * @return 시간대 슬롯 (08-16, 16-24, 00-08)
     */
    public static String determineTimeSlot(Shift shift) {
        String code = extract(shift);
        return switch (code) {
            case "D" -> "08-16";
            case "E" -> "16-24";
            case "N" -> "00-08";
            default -> "UNKNOWN";
        };
    }
}
