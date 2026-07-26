# Phase 7 → Phase 8 handoff

## 상태

- Phase 7 판정: **PASS**
- Phase 8 기술적 착수 가능 여부: **가능**
- 자동 착수 여부: **불가 — 별도 사용자 승인 필요**
- production 기본값: `OPTAPLANNER_ONLY`
- 배포/push/운영 전환: 수행하지 않음
- 잠긴 holdout: 수행하지 않음

## 고정 기준

- worktree: `/Users/brown/.codex/worktrees/c574-phase7-shadow`
- branch: `codex/phase7-shadow-preparation`
- 시작 commit: `d33493d03fba372da6c33f4e6c815d701cbaf778`
- Phase 7 구현 commit: `aefda24`
- candidate fingerprint: `36c39f3414ef11e6d2eba932424b055be19c2924ea69186126ccb5b3c8764218`
- candidate: `ALNS_THEN_ORDERED_VND_PRECEPTOR_PREFIX_REASSIGN`

이 fingerprint 이후 잠긴 holdout 결과를 보고 후보 구현, budget 배분, operator, objective, seed를 조정하면 안 된다. 변경이 필요하면 새 후보 fingerprint와 새 검증 계획으로 별도 phase를 시작해야 한다.

## 구현된 전환 모드

| mode | 사용자에게 반환 | 비교 관측 |
|---|---|---|
| `OPTAPLANNER_ONLY` | OptaPlanner | 없음 |
| `OPTAPLANNER_PRIMARY_SHADOW_POJO` | OptaPlanner | POJO |
| `POJO_PRIMARY_SHADOW_OPTAPLANNER` | POJO | OptaPlanner |
| `POJO_ONLY` | POJO | 없음 |

shadow는 반환을 바꾸지 않는다. primary 실패에는 fallback이 없고, shadow 실패는 error와 구조화 메트릭에 남지만 primary 결과는 보존한다.

## Phase 7 증거

authoritative validation:

- 입력: fairness/preceptor/request/sample
- seed: `1501,1502`
- engine budget: 각 60초
- warm-up 제외
- crossed order
- 결과: POJO `3W/5T/0L`
- Opta/POJO feasible: `8/8`
- soft2 paired delta p10/median/p90: `-1180/0/2`
- score mismatch/state corruption/pinned change/rollback failure: 모두 `0`

artifact root:

`/Users/brown/.codex/worktrees/c574-phase7-shadow/benchmark-artifacts/phase7/`

주요 결과:

- `candidate-reproduction/wall-60s-20260726T225500Z/`
- `shadow-smoke/observability-wall-1s-20260726T231100Z/`
- `shadow-smoke/fixed-20260726T225300Z/`

전체 테스트:

```bash
cd /Users/brown/.codex/worktrees/c574-phase7-shadow
./mvnw test
```

결과: `353 tests, 0 failures, 0 errors, 8 skipped`, `BUILD SUCCESS`.

## Phase 8 시작 전 필수 보호 조건

1. 별도 Phase 8 worktree와 브랜치를 만들고 시작 commit을 명시한다.
2. Phase 7 candidate fingerprint를 변경하지 않는다.
3. production 기본값을 바꾸기 전에 `OPTAPLANNER_PRIMARY_SHADOW_POJO`부터 제한된 범위로 사용한다.
4. 배포, 환경 변수 변경, traffic 전환, 잠긴 holdout은 각각 명시적 사용자 승인을 받는다.
5. shadow failure, timeout, full/incremental mismatch, pinned change, rollback failure, state corruption 중 하나라도 발생하면 POJO promotion을 중단한다.
6. primary 결과와 외부 API/Firestore snapshot이 기존 계약과 동일한지 확인한다.
7. 동기 shadow의 CPU·latency 증가를 capacity와 timeout budget에 포함한다.
8. 관측 기간과 표본 수, stop condition, rollback 절차를 배포 전에 고정한다.

## 권장 Phase 8 순서

1. `OPTAPLANNER_ONLY` baseline의 latency/error/score 로그를 고정한다.
2. 제한된 비production 또는 승인된 canary에서 `OPTAPLANNER_PRIMARY_SHADOW_POJO`를 활성화한다.
3. primary 반환 불변, 외부 contract snapshot 불변, 안전성 count 0을 확인한다.
4. W/T/L과 최초 차이 목적식, assignment diff, 엔진별 elapsed를 분석한다.
5. 사전에 정한 표본 수와 시간 창을 충족한 뒤에만 POJO-primary 여부를 결정한다.
6. `POJO_PRIMARY_SHADOW_OPTAPLANNER`는 별도 promotion 승인 뒤에만 사용한다.
7. `POJO_ONLY`는 shadow 검증과 rollback rehearsal을 모두 통과한 뒤 별도로 승인한다.

## 잠긴 holdout

Phase 7에서는 승인되지 않아 실행하지 않았다. 승인할 경우 아래 명령만 사용한다.

```bash
cd /Users/brown/.codex/worktrees/c574-phase7-shadow
PHASE7_LOCKED_HOLDOUT_APPROVED=true \
OUTPUT_DIR=/Users/brown/.codex/worktrees/c574-phase7-shadow/benchmark-artifacts/phase7/locked-holdout/wall-60s-<UTC_TIMESTAMP> \
/Users/brown/.codex/worktrees/c574-phase7-shadow/scripts/benchmark/run-phase7-shadow-benchmark.sh locked-holdout
```

예상 파일은 `raw.jsonl`, `summary.json`, `report.md`다. 결과는 사후 평가이며 tuning 입력으로 사용하지 않는다.

## 재현 명령

Phase 6 최종 후보 60초 재현:

```bash
cd /Users/brown/.codex/worktrees/c574-phase7-shadow
OUTPUT_DIR=/Users/brown/.codex/worktrees/c574-phase7-shadow/benchmark-artifacts/phase7/candidate-reproduction/wall-60s-<UTC_TIMESTAMP> \
PARTITION=phase7-phase6-candidate-reproduction-60s \
SEEDS=1501,1502 \
WARMUP=true \
/Users/brown/.codex/worktrees/c574-phase7-shadow/scripts/benchmark/run-phase7-shadow-benchmark.sh validation-60
```

관측 smoke:

```bash
cd /Users/brown/.codex/worktrees/c574-phase7-shadow
OUTPUT_DIR=/Users/brown/.codex/worktrees/c574-phase7-shadow/benchmark-artifacts/phase7/shadow-smoke/wall-3s-<UTC_TIMESTAMP> \
SEEDS=1603 \
WARMUP=true \
WALL_SECONDS=3 \
/Users/brown/.codex/worktrees/c574-phase7-shadow/scripts/benchmark/run-phase7-shadow-benchmark.sh smoke-wall
```

패키지:

```bash
cd /Users/brown/.codex/worktrees/c574-phase7-shadow
./mvnw -DskipTests \
  -Dquarkus.container-image.build=false \
  -Dquarkus.container-image.push=false \
  clean package
```

## 알려진 제한과 사용자 결정

- production traffic shadow 증거는 아직 없다.
- validation은 네 입력과 두 seed다.
- 동기 shadow는 latency와 CPU를 증가시킨다.
- 로컬 Docker 데몬이 없어 Jib의 `docker load`는 검증하지 못했다.
- holdout은 승인되지 않아 미실행이다.

Phase 8 착수를 위해 사용자가 결정해야 하는 사항:

- 별도 Phase 8 세션/브랜치 시작 승인
- shadow 실행 환경과 traffic 범위
- 관측 기간·표본 수·latency budget
- 배포 승인과 즉시 rollback 기준
- 잠긴 holdout을 실행할지 여부

Phase 7 PASS만으로 production 기본값이나 외부 운영 상태를 변경해서는 안 된다.
