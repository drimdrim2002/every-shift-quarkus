package org.acme.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.acme.solver.core.RosterScore;

/**
 * 엔진 중립 점수를 기존 Firestore/API 호환 필드로 투영합니다.
 */
public final class ScoreProjection {

    private ScoreProjection() {
    }

    public static Map<String, Object> toFirestoreFields(RosterScore score) {
        Objects.requireNonNull(score, "score");

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("hardScore", score.hardScore());

        // 현재 1h/4s 구조에 존재하지 않는 과거 레벨은 기존 계약대로 0을 저장합니다.
        fields.put("night48RestSoftScore", 0);
        fields.put("night32RestSoftScore", score.softScore(0));
        fields.put("undesiredSoftScore", score.softScore(1));
        fields.put("threeConsecutiveNightSoftScore", 0);
        fields.put("fairnessSoftScore", score.softScore(2));
        fields.put("desiredSoftScore", score.softScore(3));

        // 하위 호환 alias는 통합 형평성 레벨과 같은 값을 유지합니다.
        fields.put("burdenFairnessSoftScore", score.softScore(2));
        fields.put("fairSoftScore", score.softScore(2));
        return Collections.unmodifiableMap(fields);
    }
}
