# Phase 0 OptaPlanner 기준선

> **이력 문서:** OptaPlanner 제거 직전(Phase 0) 스냅샷이다.
> **후속 변경(2026-08):** Night→Day 32h(NOD)는 **hard**로 승격되었고,
> soft 순서는 `undesired / fairness / desired / reserved` 이다.
> 현재 계약은 `src/test/resources/contracts/score-contract-1h4s.json` 을 본다.

## 목적과 범위

이 문서는 OptaPlanner 제거 전에 당시 `1 hard + 4 soft` 점수 의미, Firestore/API 투영 계약, 제약 결과와 성능 기준선을 재현 가능하게 고정한다. Phase 0 시점에는 제약 산식, 점수 우선순위, API 스키마와 solver 구현을 변경하지 않았다.

당시 점수 벡터는 다음 순서로 큰 값이 우수한 사전식 비교를 사용했다.

| 인덱스 | 의미 (Phase 0 당시) | feasible/비교 계약 |
|---|---|---|
| `hard[0]` | 필수 제약 위반 | `0` 이상이면 feasible이며 모든 soft보다 우선 |
| `soft[0]` | 야간 후 32시간 휴식 | `soft[1..3]`보다 우선 |
| `soft[1]` | 기피일 배정 | `soft[2..3]`보다 우선 |
| `soft[2]` | 야간·휴일·주/저녁 통합 형평성 | `soft[3]`보다 우선 |
| `soft[3]` | 희망일 배정 | 가장 낮은 우선순위 |

계약 테스트는 다음을 고정한다.

- `ScoreContractCharacterizationTest`: `@PlanningScore`의 1 hard/4 soft 크기, 사전식 순서, feasible 의미
- `ScoreContractSnapshotTest`: 4-soft 점수를 현재 Firestore camelCase 필드와 status API snake_case 필드로 투영한 golden snapshot
- `EmployeeSchedulingConstraintProviderTest`: 기존 ConstraintVerifier 52개 결과와 경계값. Phase 0에서는 기대값을 변경하지 않는다.
- Golden snapshot: `src/test/resources/contracts/score-contract-1h4s.json`

## 입력과 golden output 분류

benchmark 입력:

- `src/test/resources/json/fairness.json`
- `src/test/resources/json/preceptor.json`
- `src/test/resources/json/request.json`
- `src/test/resources/json/sample.json`

빠른 회귀·warm-up 입력:

- `src/test/resources/json/fairness_test.json`

benchmark 입력이 아닌 golden output 후보:

- `src/test/resources/json/preceptor_output.json`
- `src/test/resources/json/schedule-20260215-001116.json`

## JSONL artifact 형식

artifact schema version은 `1`이다. 한 파일은 다음 세 record type으로 구성된다.

| record type | 핵심 필드 |
|---|---|
| `metadata` | 환경, OS/JDK/CPU/heap, git commit/dirty, dataset/seed/profile, solver config fingerprint |
| `run` | dataset/input hash, seed/repeat/profile, score vector, feasible, 실행시간, best 도달시간, best 변경 횟수, 실제 score calculation 수, heap 사용량, assignment SHA-256 |
| `repeat_summary` | 같은 dataset/seed/profile 반복의 점수·assignment 안정성, 실행시간/best 도달시간 범위 |

assignment 원문과 직원 식별자는 artifact에 기록하지 않고 정렬된 배정의 SHA-256 fingerprint만 저장한다. `heap_peak_used_bytes`는 각 run 직전에 JVM heap memory pool peak를 reset한 뒤 수집한 합계다. 프로세스 RSS는 포함하지 않는다.

OptaPlanner는 전체 solver step 수를 public API로 제공하지 않는다. Phase 0에서는 후보 평가의 재현 기준인 실제 `score_calculation_count`와 best 변경 횟수를 기록한다. fixed-evaluation run은 실제 계산 수가 요청한 `evaluation_limit`과 같은지도 검증한다.

## 프로필 분리

`wall-clock`과 `fixed-evaluations`는 서로 다른 계약이다.

- `wall-clock`: 운영 비용과 제한시간 내 품질 측정. 같은 seed라도 CPU/JIT/GC 타이밍에 따라 평가 횟수와 최종 결과가 달라질 수 있다.
- `fixed-evaluations`: 알고리즘 품질과 재현성 측정. 단일 스레드(`NONE`), `REPRODUCIBLE`, 고정 seed, 고정 score calculation budget을 사용한다.
- warm-up은 `fairness_test.json`으로 프로필별 한 번 실행하지만 artifact 집계에는 포함하지 않는다.
- 실행 순서는 seed/repeat마다 두 프로필을 교차해 열 상태 편향을 줄인다.

## 테스트 명령

계약과 ConstraintVerifier 집중 테스트:

```bash
./mvnw -Dtest=ScoreContractCharacterizationTest,ScoreContractSnapshotTest,EmployeeSchedulingConstraintProviderTest test
```

전체 테스트:

```bash
./mvnw test
```

benchmark test는 기본 전체 테스트에서 skip되며 `-Dbenchmark.enabled=true`로만 실행된다.

## macOS 로컬 기준선

체크인된 빠른 기준선 재실행 명령:

```bash
./mvnw \
  -Dtest=OptaPlannerBaselineBenchmarkTest \
  -Dbenchmark.enabled=true \
  -Dbenchmark.datasets=fairness.json,preceptor.json,request.json,sample.json \
  -Dbenchmark.seeds=42,43 \
  -Dbenchmark.repeats=2 \
  -Dbenchmark.profiles=wall-clock,fixed-evaluations \
  -Dbenchmark.wall-clock-seconds=2 \
  -Dbenchmark.evaluation-limit=50000 \
  -Dbenchmark.warmup=true \
  -Dbenchmark.environment=macos-local \
  -Dbenchmark.run-label=phase0-quick-baseline-2s-50k \
  -Dbenchmark.output=benchmark-artifacts/phase0/macos-local/2026-07-15-quick-baseline.jsonl \
  test
```

현재 artifact:

- `benchmark-artifacts/phase0/macos-local/2026-07-15-quick-baseline.jsonl`
- 환경: macOS arm64, Temurin Java 21.0.2, 단일 solver thread
- run: 32개, repeat summary: 16개
- feasible: 24/32. 2초/50,000 평가의 짧은 예산에서 `preceptor.json` 8개 run은 모두 infeasible이며, 이는 품질 승인 기준이 아니라 현재 동작 기준선이다.
- fixed-evaluation: 8/8 dataset/seed summary에서 점수와 assignment가 반복 간 동일
- wall-clock: 7/8 summary에서 점수와 assignment가 반복 간 동일. `preceptor.json`, seed 42는 반복 간 변동

fixed 50,000 평가의 대표 점수 벡터는 다음과 같다. 동일 seed 반복은 모두 같은 점수다.

| 입력 | seed | score vector | feasible |
|---|---:|---|---|
| `fairness.json` | 42 | `[0]hard/[0/0/-7630/0]soft` | true |
| `fairness.json` | 43 | `[0]hard/[0/0/-7628/0]soft` | true |
| `preceptor.json` | 42 | `[-5]hard/[-3840/-13440/-6593/0]soft` | false |
| `preceptor.json` | 43 | `[-6]hard/[-1920/-8640/-6937/0]soft` | false |
| `request.json` | 42 | `[0]hard/[0/0/-5279/0]soft` | true |
| `request.json` | 43 | `[0]hard/[0/0/-5281/0]soft` | true |
| `sample.json` | 42 | `[0]hard/[0/0/-5713/0]soft` | true |
| `sample.json` | 43 | `[0]hard/[0/0/-5713/0]soft` | true |

### 60초·10 seed 정규 기준선

먼저 wall-clock artifact를 생성한다.

```bash
./mvnw \
  -Dtest=OptaPlannerBaselineBenchmarkTest \
  -Dbenchmark.enabled=true \
  -Dbenchmark.datasets=fairness.json,preceptor.json,request.json,sample.json \
  -Dbenchmark.seeds=42,43,44,45,46,47,48,49,50,51 \
  -Dbenchmark.repeats=2 \
  -Dbenchmark.profiles=wall-clock \
  -Dbenchmark.wall-clock-seconds=60 \
  -Dbenchmark.environment=macos-local \
  -Dbenchmark.run-label=phase0-wall-clock-60s \
  -Dbenchmark.output=benchmark-artifacts/phase0/macos-local/wall-clock-60s.jsonl \
  test
```

그 artifact의 dataset별 실제 `score_calculation_count` 분포를 검토해 합의한 고정 평가 예산을 정한 뒤 별도 fixed-evaluation artifact를 생성한다. wall-clock 결과와 fixed-evaluation 결과를 같은 파일이나 같은 승인 지표로 섞지 않는다.

```bash
FIXED_EVALUATION_LIMIT=2500000

./mvnw \
  -Dtest=OptaPlannerBaselineBenchmarkTest \
  -Dbenchmark.enabled=true \
  -Dbenchmark.datasets=fairness.json,preceptor.json,request.json,sample.json \
  -Dbenchmark.seeds=42,43,44,45,46,47,48,49,50,51 \
  -Dbenchmark.repeats=2 \
  -Dbenchmark.profiles=fixed-evaluations \
  -Dbenchmark.evaluation-limit="$FIXED_EVALUATION_LIMIT" \
  -Dbenchmark.environment=macos-local \
  -Dbenchmark.run-label=phase0-fixed-evaluations \
  -Dbenchmark.output=benchmark-artifacts/phase0/macos-local/fixed-evaluations.jsonl \
  test
```

위 `2500000`은 실행 예시이며 승인된 기본값이 아니다. 실제 값은 바로 앞의 60초 wall-clock 분포로 확정한다.

## 단일 컨테이너 기준선

컨테이너 artifact는 반드시 `benchmark.environment=container`와 `benchmark-artifacts/phase0/container/` 경로를 사용해 macOS 로컬 결과와 분리한다. Cloud Run Job 설정과 같은 CPU 8, memory 4 GiB를 사용한다.

```bash
docker run --rm \
  --cpus=8 \
  --memory=4g \
  -e MAVEN_OPTS=-Xmx3g \
  -v "$PWD":/workspace \
  -v "$HOME/.m2":/root/.m2 \
  -w /workspace \
  maven:3.9.9-eclipse-temurin-21 \
  ./mvnw \
    -Dtest=OptaPlannerBaselineBenchmarkTest \
    -Dbenchmark.enabled=true \
    -Dbenchmark.datasets=fairness.json,preceptor.json,request.json,sample.json \
    -Dbenchmark.seeds=42,43,44,45,46,47,48,49,50,51 \
    -Dbenchmark.repeats=2 \
    -Dbenchmark.profiles=wall-clock \
    -Dbenchmark.wall-clock-seconds=60 \
    -Dbenchmark.environment=container \
    -Dbenchmark.run-label=phase0-container-wall-clock-60s \
    -Dbenchmark.output=benchmark-artifacts/phase0/container/wall-clock-60s.jsonl \
    test
```

현재 macOS 실행 환경에는 Docker/Podman/Colima/Finch/containerd 런타임이 없어 컨테이너 baseline을 실행하지 않았다. 배포나 원격 Cloud Run 실행도 Phase 0 요청 범위와 “배포 금지” 조건 때문에 수행하지 않았다. 상태는 `benchmark-artifacts/phase0/container/README.md`에 기록한다.

## 비교 규칙

- hard score를 먼저 비교하고, soft는 `[0]`부터 `[3]`까지 처음 다른 레벨만 비교한다. soft 합계로 비교하지 않는다.
- fixed-evaluation에서 같은 input hash/seed/config fingerprint의 점수나 assignment fingerprint가 달라지면 재현성 회귀다.
- wall-clock의 반복 변동은 허용 가능하지만 반드시 summary에 남기고 평가 횟수·best 도달시간과 함께 해석한다.
- 환경이 다른 artifact는 직접 성능 회귀 판정에 섞지 않는다.
- `preceptor.json`의 짧은 예산 infeasible 결과처럼 기존 기준선의 약점도 삭제하거나 정상화하지 않고 그대로 비교 기준으로 보존한다.

## Phase 6 비열화·tail 수치 측정

OptaPlanner와 baseline-only POJO_ALNS의 정규 paired 비교, 엔진 실행 순서 교차,
p10/median/p90 score, p95 시간, rollback 및 operator 기여 측정은
[`PHASE6_GATE_MEASUREMENT.md`](./PHASE6_GATE_MEASUREMENT.md)의 전용 harness를 사용한다.

먼저 wall-clock artifact를 생성해 엔진별 실제 평가량 분포를 확인한다.

```bash
./scripts/benchmark/run-phase6-benchmark.sh wall-clock
```

그 결과로 두 엔진의 fixed-evaluation 예산을 각각 확정한 뒤 별도 artifact를 생성한다.
수치 확정 전에는 candidate operator를 기본 활성화하지 않는다.
