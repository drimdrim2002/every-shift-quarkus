package org.acme.solver.move;

import java.time.LocalDateTime;
import java.util.Objects;

/** 변경 shift 주변의 인접 휴식 재평가 시간 범위입니다. */
public record RestWindow(LocalDateTime startInclusive, LocalDateTime endInclusive) {

    public RestWindow {
        Objects.requireNonNull(startInclusive, "startInclusive");
        Objects.requireNonNull(endInclusive, "endInclusive");
        if (endInclusive.isBefore(startInclusive)) {
            throw new IllegalArgumentException("rest window 종료는 시작보다 빠를 수 없습니다.");
        }
    }
}
