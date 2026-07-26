package org.acme.solver.score;

/** 증분 제약 캐시 오염을 감지하는 내부 fingerprint입니다. */
public record ScoreCacheFingerprint(long xorLane, long sumLane, int cellCount) {
}
