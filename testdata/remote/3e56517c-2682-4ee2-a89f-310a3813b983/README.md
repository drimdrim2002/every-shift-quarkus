# Remote execution snapshot: `3e56517c-2682-4ee2-a89f-310a3813b983`

Cloud Run Job 원격 실행을 로컬에서 재현하기 위한 입력 스냅샷.

## 출처

| 항목 | 값 |
|------|-----|
| executionId | `3e56517c-2682-4ee2-a89f-310a3813b983` |
| Cloud Run Job | `every-shift-job` |
| Cloud Run Execution | `every-shift-job-wz7cb` |
| Project / Region | `every-shift-api` / `asia-northeast3` |
| 시각 (UTC) | 2026-08-08 04:18:11 ~ 04:19:26 |
| 원격 Score | `[0]hard/[-480/0/-6301/0]soft` |

입력은 Job 컨테이너 args의 `--input-data` (Base64)를 디코딩해 저장했다.
상세 메타는 `META.json` 참고.

## 파일

| 파일 | 설명 |
|------|------|
| `input.json` | PlanningRequest (로컬 재현용, pretty JSON) |
| `META.json` | 원격 실행 메타데이터 |
| `schedule-soft0-minus480.json` | soft[0]=-480 로컬 배정 스냅샷 (진단용) |
| `schedule-soft0-zero.json` | soft[0]=0 로컬 배정 스냅샷 (가능 해 존재 증거) |
| 테스트 리소스 | `src/test/resources/json/remote/3e56517c-2682-4ee2-a89f-310a3813b983.json` |

## Night→Day 32h (NOD) — 점수 레이아웃 변경 후

- **이전**: soft[0]=`NIGHT_TO_DAY_REST` (예: soft[0]=-480)
- **현재**: 동일 규칙이 **hard** (`NightToDayRestPreference` → `ScoreLevel.HARD`)
- soft 순서: soft[0]=undesired, soft[1]=fairness, soft[2]=desired, soft[3]=reserved
- 과거 soft[0]=-480 배정 스냅샷을 재계산하면 hard=-480 으로 집계됨
- **진단 테스트**: `./mvnw -Dtest=NightToDaySoft0BreakdownTest test`

## 로컬 실행

### 권장: Maven 테스트 재현

```bash
# 프로젝트 루트에서
./scripts/run-remote-replay.sh

# 또는 직접
./mvnw -Dtest=RemoteExecutionReplayTest test
```

- 솔버 시간: 테스트 프로필 기본 60초 (`%test.solver.termination.spent-limit`)
- 결과: `target/schedule-output/schedule-*.json` (dev/test export)

시간을 바꾸려면:

```bash
./scripts/run-remote-replay.sh test 30
# 또는
SPENT_LIMIT=30 ./scripts/run-remote-replay.sh
```

### 대안: JOB 모드 (Cloud Run Job 엔트리와 동일)

```bash
./scripts/run-remote-replay.sh job
# 또는 수동
./mvnw quarkus:dev \
  -Dapp.mode=JOB \
  -Dquarkus.args="--input-file testdata/remote/3e56517c-2682-4ee2-a89f-310a3813b983/input.json" \
  -Dsolver.termination.spent-limit=60
```

`--execution-id`는 넘기지 않는다 (로컬에서 Firestore 상태 갱신 생략).

## 기대 결과 해석

- **Hard score = 0**: 원격과 동일하게 feasible 기대
- **Soft score**: 시드/시간 예산/엔진 차이에 따라 원격 `[-480/0/-6301/0]` 과 다를 수 있음
- 원격 환경은 prod spent-limit 60초 근처에서 약 1분 소요

## 관련 문서

- `docs/CLOUD_RUN_REMOTE_EXECUTION_LOOKUP.md` — 원격 로그 조회 방법
