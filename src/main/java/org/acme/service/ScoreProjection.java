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

        // soft 순서: soft[0]=undesired, soft[1]=fairness, soft[2]=desired, soft[3]=예약(0).
        // Night→Day 32h(NOD)는 hard로 이전되어 night32RestSoftScore는 하위 호환용 0입니다.
        fields.put("night48RestSoftScore", 0);
        fields.put("night32RestSoftScore", 0);
        fields.put("undesiredSoftScore", score.softScore(0));
        fields.put("threeConsecutiveNightSoftScore", 0);
        fields.put("fairnessSoftScore", score.softScore(1));
        fields.put("desiredSoftScore", score.softScore(2));

        // 하위 호환 alias는 통합 형평성 레벨과 같은 값을 유지합니다.
        fields.put("burdenFairnessSoftScore", score.softScore(1));
        fields.put("fairSoftScore", score.softScore(1));
        return Collections.unmodifiableMap(fields);
    }
}
