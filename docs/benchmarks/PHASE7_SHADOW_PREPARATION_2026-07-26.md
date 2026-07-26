# Phase 7 POJO shadow 비교 및 점진적 전환 준비

## 최종 판정

**PASS**

Phase 7의 로컬 구현·회귀·differential·validation 조건을 충족했다. 다만 이 판정은 production 전환이나 배포 승인이 아니다. production 기본값은 기존 OptaPlanner 반환 경로이며, push·배포·외부 운영 전환·잠긴 holdout 실행은 수행하지 않았다.

- 전용 worktree: `/Users/brown/.codex/worktrees/c574-phase7-shadow`
- 브랜치: `codex/phase7-shadow-preparation`
- 시작 커밋: `d33493d03fba372da6c33f4e6c815d701cbaf778`
- 검증된 구현 커밋: `aefda24` (`feat: add phase 7 solver shadow preparation`)
- 후보 fingerprint: `36c39f3414ef11e6d2eba932424b055be19c2924ea69186126ccb5b3c8764218`
- 작성일: 2026-07-26, Asia/Seoul

## 시작 감사와 Phase 6 후보 재현

시작 시 지정 경로, 브랜치, HEAD, clean 상태를 확인했다. 현재 브랜치에는 Phase 6 문서가 선택한 다음 구현이 실제로 없었다.

- `PreceptorPrefixGuidedProtectedReassignSelector`
- exhaustive preceptor-prefix reassign intensification
- `ALNS_THEN_ORDERED_VND_PRECEPTOR_PREFIX_REASSIGN` 후보

문서 결과만으로 shadow 전환을 주장하지 않았다. 읽기 전용으로 `/private/tmp/every-shift-quarkus-preceptor-diagnostic`의 코드·테스트·artifact를 감사하고, 최소 범위의 후보 구현과 회귀 테스트를 현재 Phase 7 브랜치에 재현했다.

현재 브랜치에서 `fairness/preceptor/request/sample` 네 입력, seed `1501,1502`, 엔진별 60초, warm-up 제외, crossed order 조건으로 Phase 6의 `3승/5무/0패`를 동일하게 재현했다. 따라서 이후 shadow 준비는 문서 추정이 아니라 현재 브랜치의 실행 증거를 근거로 한다.

## 모드와 반환 계약

`solver.engine`은 아래 네 값만 허용한다. 명시되지 않은 기본값은 `OPTAPLANNER_ONLY`다.

| 모드 | 반환 엔진 | 관측 전용 엔진 | 동작 |
|---|---|---|---|
| `OPTAPLANNER_ONLY` | OptaPlanner | 없음 | 기존 production 기본 반환 경로 |
| `OPTAPLANNER_PRIMARY_SHADOW_POJO` | OptaPlanner | POJO | POJO 결과는 비교·관측만 하고 응답을 바꾸지 않음 |
| `POJO_PRIMARY_SHADOW_OPTAPLANNER` | POJO | OptaPlanner | OptaPlanner 결과는 비교·관측만 하고 응답을 바꾸지 않음 |
| `POJO_ONLY` | POJO | 없음 | 명시적으로 선택한 경우에만 POJO 반환 |

설정은 `solver.engine=${SOLVER_ENGINE:OPTAPLANNER_ONLY}`로 고정했다. 과거 별칭이나 오타는 암묵적으로 수용하지 않고 즉시 거부한다.

silent fallback은 없다.

- primary 실패: 예외를 그대로 전파하며 shadow 결과로 대체하지 않는다.
- shadow 실패: error log와 `shadowFailureClass/message`에 명시적으로 기록하지만 primary assignment와 score를 변경하지 않는다.
- shadow에는 `SolveListener.noop()`을 사용해 사용자 진행 이벤트를 오염시키지 않는다.
- 반환 `SolveResult`의 assignment와 score는 모드가 지정한 primary 결과다.

## 구조화 관측

각 실행은 후보 fingerprint와 mode/options/seed 기반 config fingerprint를 함께 남긴다. 엔진별 관측값은 다음을 포함한다.

- elapsed, termination reason, seed, 전체 hard/soft score 벡터
- complete, final full-score 검증, pinned assignment 변경 시도
- full-score mismatch와 incremental-score mismatch
- initial solution failure, repair failure
- rollback 시도·실패, state corruption, timeout
- operator별 선택·accept·reject·global-best 기여
- POJO 기준 사전식 W/T/L, 최초 차이 목적식, assignment 차이 수
- shadow 예외 클래스와 메시지

primary와 shadow의 최종 결과는 독립적으로 complete/pinned/full-score를 검사한다. primary의 complete·pinned·full-score 최종 검증이 실패하면 반환 termination reason을 각각 `STATE_CORRUPTION` 또는 `SCORE_MISMATCH`로 승격한다.

POJO 후보의 ALNS, ordered VND, exhaustive prefix 단계는 기존 transaction rollback/fingerprint 및 full/incremental score 검증을 유지한다. 단계별 메트릭을 상위 coordinator가 중복 없이 집계한다.

## API 및 Firestore 계약

API DTO와 Firestore 저장 계약은 변경하지 않았다. 관측값은 내부 `SolveMetrics` 구현과 구조화 로그로 추가했으며 사용자 응답 스키마에 필드를 추가하지 않았다.

다음 계약·호환성 테스트가 통과했다.

- `ScoreContractSnapshotTest`
- `FullScoreCalculatorDifferentialTest`
- `OptaPlannerEngineCompatibilityTest`
- 기존 exporter/API/service 테스트를 포함한 전체 `./mvnw test`

## 테스트 결과

### 집중 회귀

```bash
cd /Users/brown/.codex/worktrees/c574-phase7-shadow
./mvnw -q \
  -Dtest=ShadowSolverCoordinatorTest,SolverEngineSelectionTest,Phase6HybridVndLongBenchmarkTest,ScoreContractSnapshotTest,FullScoreCalculatorDifferentialTest,OptaPlannerEngineCompatibilityTest,PreceptorPrefixGuidedProtectedReassignSelectorTest,ExhaustivePrefixReassignIntensificationEngineTest,OrderedVndLocalSearchEngineTest,AlnsChangeSwapVndHybridSolverEngineTest \
  test
```

결과: 성공.

다음 동작을 직접 고정했다.

- 작은 fixture에서 네 mode의 primary/shadow 역할
- shadow가 primary 반환 assignment·score·listener를 바꾸지 않음
- primary 실패에 silent fallback이 없음
- shadow 실패가 숨겨지지 않고 관측됨
- 결정론적 candidate/config fingerprint
- 사전식 W/T/L, 최초 차이 목적식, assignment diff
- rollback/operator 집계
- full-score mismatch와 pinned 변경 감지
- Opta/POJO 동일 assignment differential score
- preceptor-prefix selector와 exhaustive intensification 회귀

### 전체 테스트

```bash
cd /Users/brown/.codex/worktrees/c574-phase7-shadow
./mvnw test
```

결과:

- `Tests run: 353`
- `Failures: 0`
- `Errors: 0`
- `Skipped: 8` — 승인형 장기 benchmark/diagnostic 테스트
- `BUILD SUCCESS`
- 총 실행 시간 `05:29`

테스트 중 출력된 score mismatch, listener failure, shadow failure stack trace는 실패 주입 회귀 테스트의 기대 로그이며 Maven 결과에는 실패나 오류가 없다.

## 벤치마크 프로토콜

- 입력: `fairness.json`, `preceptor.json`, `request.json`, `sample.json`
- warm-up: 실행하되 집계에서 제외
- 순서: seed/dataset 기준 crossed order
- 비교: `hard > soft[0] > soft[1] > soft[2] > soft[3]`
- W/T/L: POJO 관점, 최초 차이 목적식으로 판정
- fixed-evaluation과 wall-clock artifact를 분리
- 고정 evaluation 단위는 Opta score calculation과 POJO candidate evaluation이 서로 다르므로 처리량·동일 비용 품질 gate로 해석하지 않음
- authoritative 재현은 분리 validation seed `1501,1502`의 엔진별 60초 wall-clock 결과

## 60초 candidate 재현 결과

artifact:

- `benchmark-artifacts/phase7/candidate-reproduction/wall-60s-20260726T225500Z/raw.jsonl`
- `benchmark-artifacts/phase7/candidate-reproduction/wall-60s-20260726T225500Z/summary.json`
- `benchmark-artifacts/phase7/candidate-reproduction/wall-60s-20260726T225500Z/report.md`

전체 8개 paired case:

| 항목 | 결과 |
|---|---:|
| Opta/POJO feasible | `8/8` |
| POJO W/T/L | `3/5/0` |
| soft2 paired delta p10/median/p90 | `-1180 / 0 / 2` |
| 전체 soft 중앙값 비열화 | 없음 (`median delta = 0`) |
| rollback 시도 | `201970` |
| score mismatch | `0` |
| state corruption | `0` |
| pinned assignment 변경 | `0` |
| assignment 차이 합계 | `2193` |
| elapsed p95 Opta/POJO | `60202 / 62864 ms` |

dataset별 W/T/L:

- fairness: `1/1/0`
- preceptor: `2/0/0`
- request: `0/2/0`
- sample: `0/2/0`

최초 차이 목적식은 `soft[0]` 1승, `soft[1]` 1승, `soft[2]` 1승, tie 5건이었다. repair failure와 operator exception도 0이었다.

POJO의 outer elapsed에는 initial solution build와 각 단계 전환 검증이 포함되므로, stage budget 합과 outer elapsed는 같은 값일 필요가 없다.

## 관측 필드 smoke

최신 구조화 필드를 포함한 1초 wall-clock smoke를 분리 seed `1603`으로 실행했다.

artifact:

- `benchmark-artifacts/phase7/shadow-smoke/observability-wall-1s-20260726T231100Z/raw.jsonl`
- `benchmark-artifacts/phase7/shadow-smoke/observability-wall-1s-20260726T231100Z/summary.json`
- `benchmark-artifacts/phase7/shadow-smoke/observability-wall-1s-20260726T231100Z/report.md`

결과:

- POJO feasible `4/4`
- full-score mismatch `0`
- incremental-score mismatch `0`
- pinned 변경 `0`
- rollback failure `0`
- state corruption `0`
- repair failure `0`
- operator exception `0`

1초 smoke의 W/T/L `1/1/2`는 관측 스키마와 안전성 검증용이며 품질 gate로 사용하지 않았다.

## fixed-evaluation smoke

`fixed-20260726T225300Z`는 Opta `50000`, POJO `5000` evaluation, seed `1601,1602`로 실행했다. POJO feasible `8/8`, mismatch/corruption/pinned/rollback failure 0을 확인했다.

고정 evaluation의 엔진별 단위가 다르고 Opta preceptor가 `0/2` feasible이어서 품질 승격 근거로 사용하지 않았다.

`fixed-20260726T225000Z`는 Opta `5000` evaluation으로 시도했으나 initial solution을 완성하지 못한 calibration 실패 artifact다. 삭제하거나 성공 결과에 합치지 않고, 불충분한 예산의 증거로 보존했다.

## 빌드

애플리케이션 패키지:

```bash
cd /Users/brown/.codex/worktrees/c574-phase7-shadow
./mvnw -DskipTests \
  -Dquarkus.container-image.build=false \
  -Dquarkus.container-image.push=false \
  clean package
```

결과: `BUILD SUCCESS`.

push 없는 Jib 로컬 이미지 빌드:

```bash
cd /Users/brown/.codex/worktrees/c574-phase7-shadow
./mvnw -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.push=false \
  package
```

결과: base image digest 해석과 이미지 구성 후 `docker load`에서 실패했다. 원인은 macOS 환경에 `/var/run/docker.sock` Docker 데몬이 없기 때문이다. 애플리케이션 compile/package 실패나 registry 인증 실패가 아니다. push와 배포는 없었다.

## 잠긴 holdout

잠긴 seed `101..110` holdout은 사용자 승인 없이 실행하지 않았다. 후보 fingerprint는 먼저 고정했으며, 스크립트도 `PHASE7_LOCKED_HOLDOUT_APPROVED=true`가 없으면 exit code 3으로 거부한다.

승인 후 사후 평가로만 실행할 정확한 명령:

```bash
cd /Users/brown/.codex/worktrees/c574-phase7-shadow
PHASE7_LOCKED_HOLDOUT_APPROVED=true \
OUTPUT_DIR=/Users/brown/.codex/worktrees/c574-phase7-shadow/benchmark-artifacts/phase7/locked-holdout/wall-60s-<UTC_TIMESTAMP> \
/Users/brown/.codex/worktrees/c574-phase7-shadow/scripts/benchmark/run-phase7-shadow-benchmark.sh locked-holdout
```

예상 artifact:

- `.../raw.jsonl`
- `.../summary.json`
- `.../report.md`

holdout 결과는 사후 평가로만 기록해야 하며 후보, budget, seed, operator, objective를 다시 조정하는 데 사용하면 안 된다.

## 변경 파일 요약

- mode/coordinator/fingerprint/metrics: `src/main/java/org/acme/solver/shadow/`
- 기본 설정 및 DI: `SolverEngineProducer.java`, `application.properties`
- Phase 6 후보 재현: preceptor-prefix selector, exhaustive intensification, hybrid mode
- 테스트: mode selection, shadow 보존·실패·결정론·무결성, 후보 selector/intensification
- benchmark harness: Phase 6 장기 benchmark structured fields 확장
- 실행 보호 스크립트: `scripts/benchmark/run-phase7-shadow-benchmark.sh`
- 원시 산출물: `benchmark-artifacts/phase7/`

## 제한 사항

- shadow는 같은 프로세스에서 동기 실행하므로 활성화 시 CPU와 요청 latency가 증가한다. production traffic에서 아직 측정하지 않았다.
- validation은 네 대표 입력과 두 분리 seed에 한정된다.
- fixed-evaluation의 엔진별 단위는 동일하지 않다.
- 로컬 Docker 데몬이 없어 최종 `docker load`를 확인하지 못했다.
- 잠긴 holdout과 production canary는 실행하지 않았다.

## PASS 근거와 Phase 8

- CI용 differential/contract suite와 전체 테스트 통과
- API/Firestore 외부 계약 변경 없음
- authoritative 60초 표본에서 hard feasible `8/8`, pinned 변경·score mismatch·rollback failure·state corruption 0
- 네 mode의 반환 엔진과 관측 엔진 역할 검증
- POJO `3승/5무/0패`, paired soft 중앙값 비열화 없음
- 기본값은 OptaPlanner-only, 배포·push·운영 전환 없음
- candidate/config fingerprint와 재현 명령, artifact, limitation 문서화

따라서 **Phase 8은 기술적으로 착수 가능**하다. 그러나 이 문서는 Phase 8 시작, production 기본값 변경, 배포, holdout 실행을 승인하지 않는다. 다음 단계는 별도 사용자 승인과 handoff의 보호 조건을 따라야 한다.
