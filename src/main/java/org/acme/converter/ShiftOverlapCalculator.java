package org.acme.converter;

import java.time.LocalDateTime;

import org.acme.model.Shift;

/**
 * 시프트 시간 중복을 계산하는 유틸리티 클래스.
 * 중복 코드 제거를 위해 추출되었습니다.
 */
public class ShiftOverlapCalculator {

    /**
     * 두 시프트 간의 중복 분 수를 계산합니다.
     *
     * @param s1 첫 번째 시프트
     * @param s2 두 번째 시프트
     * @return 중복 분 수 (중복이 없으면 0)
     */
    public static int getMinuteOverlap(Shift s1, Shift s2) {
        if (s1 == null || s2 == null) {
            return 0;
        }

        LocalDateTime start1 = s1.getStart();
        LocalDateTime end1 = s1.getEnd();
        LocalDateTime start2 = s2.getStart();
        LocalDateTime end2 = s2.getEnd();

        if (start1 == null || end1 == null || start2 == null || end2 == null) {
            return 0;
        }

        // 중복 구간의 시작 시각 (둘 중 더 늦은 시각)
        LocalDateTime overlapStart = start1.isAfter(start2) ? start1 : start2;

        // 중복 구간의 종료 시각 (둘 중 더 이른 시각)
        LocalDateTime overlapEnd = end1.isBefore(end2) ? end1 : end2;

        // 중복이 없는 경우 (종료가 시작보다 빠르거나 같음)
        if (!overlapEnd.isAfter(overlapStart)) {
            return 0;
        }

        // 중복 분 수 계산
        return (int) java.time.Duration.between(overlapStart, overlapEnd).toMinutes();
    }

    /**
     * 두 시프트가 시간적으로 중복되는지 확인합니다.
     *
     * @param s1 첫 번째 시프트
     * @param s2 두 번째 시프트
     * @return 중복되면 true, 그렇지 않으면 false
     */
    public static boolean hasOverlap(Shift s1, Shift s2) {
        return getMinuteOverlap(s1, s2) > 0;
    }
}
