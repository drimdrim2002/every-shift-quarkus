# Phase 8 OptaPlanner 제거 보고서 (2026-07-26)

## 최종 판정

**PASS**

Phase 7에서 PASS된 POJO 후보를 단일 런타임 엔진으로 고정하고 OptaPlanner의 production/test
classpath, planning annotation, score/status 타입, constraint provider, adapter, shadow 실행 경로,
설정과 전용 benchmark harness를 제거했다. 전체 테스트, clean package, 외부 점수 계약,
네 운영 입력의 POJO_ONLY benchmark와 dependency/source 감사를 통과했다.

이 판정은 branch 내 구현 검증 결과다. push, 배포, Cloud Run 변경, 운영 전환, 잠긴 holdout,
외부 운영 데이터 쓰기는 수행하거나 승인하지 않았다.

- worktree: `/Users/brown/.codex/worktrees/c574-phase8-remove-optaplanner`
- branch: `codex/phase8-remove-optaplanner`
- 시작 commit: `c6a25b2c2722a8e301f4a9dc4e22056667129ec7`
- 선행조건: Phase 7 `PASS`
- 검증 후보: `ALNS_THEN_ORDERED_VND_PRECEPTOR_PREFIX_REASSIGN`

## 제거 범위

### 런타임

- `OptaPlannerSolverEngine`, score adapter와 전체 `solver/optaplanner` 경로
- `ShadowSolverCoordinator`, `SolverMode`, 비교 메트릭과 config fingerprint
- `EmployeeSchedulingConstraintProvider`
- `ShiftPinningFilter`
- `solver.engine` 및 shadow mode 설정
- `EmployeeSchedule`, `Shift`, `Employee`, `Availability`의 planning annotation
- `EmployeeSchedule`의 `BendableScore`, `SolverStatus`

`SolverEngineProducer`는 Phase 7에서 검증된 POJO hybrid를 직접 생성한다. 런타임 engine switch나
shadow fallback은 존재하지 않는다.

### 빌드

`pom.xml`에서 다음 항목을 제거했다.

- OptaPlanner BOM 및 version property
- Quarkus integration
- Jackson integration
- test dependency

`./mvnw dependency:tree '-Dincludes=org.optaplanner:*'`는 성공했고 dependency 노드를 출력하지
않았다. 전체 트리는 `benchmark-artifacts/phase8/dependency-tree.txt`에 보존했다.

### 테스트와 benchmark

- ConstraintVerifier 기반 provider 테스트 삭제
- engine adapter/compatibility/shadow 테스트 삭제
- Phase 6의 이중 엔진 benchmark·diagnostic 테스트와 실행 script 삭제
- `OperationalScoreContractTest` 추가
  - 네 운영 입력의 deterministic evaluator 결과
  - constraint vector 합과 전체 점수 일치
  - `EmployeeSchedule` projection round-trip assignment/score 보존
- `Phase8PojoOnlyBenchmarkTest`와 재현 script 추가
- 기존 POJO full/incremental/property/contract 테스트 유지

삭제된 differential 경로의 역할은 POJO evaluator 단위 경계 27개, 운영 입력 contract 4개,
10,000회 move transaction property와 full-score 재검증으로 대체했다. Phase 7 이전의
양쪽 엔진 비교 증거는 역사 benchmark artifact와 보고서로 보존했다.

## 외부 계약

API와 Firestore의 기존 점수 필드 매핑을 변경하지 않았다.

- `ScoreContractSnapshotTest`: Firestore/API golden snapshot 통과
- `EmployeeScheduleExternalContractTest`: 저장 `resultJson.score`가 기존
  `[0]hard/[.../.../.../...]soft` 문자열 계약을 유지
- `JsonScheduleExporterTest`, `ScheduleExporterTest`: export 점수 문자열과 DTO 계약 통과

내부 모델의 score는 `RosterScore`지만 외부 저장 JSON은 문자열로 투영한다.

## 점수 의미와 strict 사전식 비교

점수는 계속 `1 hard + 4 soft`다.

1. hard
2. soft[0] 야간 후 32시간 휴식
3. soft[1] 기피일 배정
4. soft[2] 통합 형평성
5. soft[3] 희망일 배정

`RosterScoreTest`, `ScoreContractCharacterizationTest`, `LexicographicSaAcceptanceTest`와
운영 입력 contract가 `hard > soft[0] > soft[1] > soft[2] > soft[3]`의 strict
lexicographic 순서를 검증했다. 낮은 우선순위의 큰 개선으로 높은 우선순위 손실을 상쇄하지
않는다.

## 테스트와 빌드

### 집중 계약

```bash
./mvnw \
  -Dtest=OperationalScoreContractTest,RosterScoreTest,ScoreContractCharacterizationTest,ScoreContractSnapshotTest,SolverEngineSelectionTest,JsonScheduleExporterTest,ScheduleExporterTest,SeededMoveSelectorTest \
  test
```

결과: `38 tests`, failures `0`, errors `0`, skipped `0`, `BUILD SUCCESS`.

### 전체 테스트

```bash
./mvnw test
```

결과:

- tests: `281`
- failures: `0`
- errors: `0`
- skipped: `3`
- `BUILD SUCCESS`
- 실행시간: `05:09`

skip 3개는 명시 승인/프로퍼티가 필요한 장기 regression 2개와 Phase 8 artifact benchmark
기본 비활성 1개다. benchmark는 아래 명시 명령으로 별도 성공 실행했다.

### clean package

```bash
./mvnw -DskipTests \
  -Dquarkus.container-image.build=false \
  -Dquarkus.container-image.push=false \
  clean package
```

결과: `BUILD SUCCESS`.

### 컨테이너 빌드

```bash
./mvnw -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.push=false \
  package
```

Jib는 base image digest
`sha256:8beb2186d175ec9cede987a467dfa3a1a0b6e6e2db508525d95436cf3f6afbc3`를 해석하고
이미지 구성까지 진행했다. 마지막 로컬 `docker load`는 macOS에 `/var/run/docker.sock`
Docker daemon이 없어 실패했다. 이는 compile/package 또는 registry 인증 실패가 아니며,
Phase 7에서 이미 기록된 동일 환경 제한이다. image push는 없었다.

## POJO_ONLY 운영 입력 benchmark

```bash
OUTPUT_DIR=/Users/brown/.codex/worktrees/c574-phase8-remove-optaplanner/benchmark-artifacts/phase8/pojo-only/fixed-20260726T233300Z \
EVALUATIONS=5000 \
SEED=1701 \
scripts/benchmark/run-phase8-pojo-only-benchmark.sh
```

profile은 fixed evaluations이며 네 입력 모두 같은 seed와 평가 예산을 썼다.

| dataset | score | elapsed ms | evaluations |
|---|---|---:|---:|
| fairness.json | `[0]hard/[0/0/-7630/0]soft` | 11182 | 5000 |
| preceptor.json | `[0]hard/[0/-4800/-6467/0]soft` | 8031 | 5000 |
| request.json | `[0]hard/[0/0/-5281/0]soft` | 10972 | 5000 |
| sample.json | `[0]hard/[0/0/-5715/0]soft` | 11933 | 5000 |

결과:

- complete: `4/4`
- hard `0`: `4/4`
- final full-score verified: `4/4`
- score mismatch: `0`
- state corruption: `0`
- rollback failure: `0`

artifact:

- `benchmark-artifacts/phase8/pojo-only/fixed-20260726T233300Z/raw.jsonl`
- `benchmark-artifacts/phase8/pojo-only/fixed-20260726T233300Z/summary.json`
- `benchmark-artifacts/phase8/pojo-only/fixed-20260726T233300Z/report.md`

이 실행은 제거 후 안전성과 재현성을 확인하는 Phase 8 fixed-evaluation smoke다. 품질 승격
근거는 후보를 동결한 Phase 7의 60초 `3W/5T/0L` 결과이며, 이번 smoke를 새 튜닝 또는
잠긴 holdout 대체로 해석하지 않는다.

## rg 감사

다음 명령은 production/test source, config, Maven 설정, 실행 script에서 일치 항목을
반환하지 않았다.

```bash
rg -n 'org\.optaplanner|OptaPlanner|BendableScore|ConstraintVerifier' \
  src/main src/test pom.xml scripts
```

추가로 `OPTAPLANNER`, `solver.engine`, `shadow` 검색도 같은 범위에서 0건이다.

문서에 남는 일치 항목은 세 종류다.

1. `docs/history/optaplanner/`: 제거 전 분석을 명시적으로 이동한 역사 자료
2. `docs/benchmarks/PHASE6_*`, `PHASE7_*`: 후보 품질과 PASS handoff 증거
3. `docs/plans/`, `docs/superpowers/plans/`: 단계적 migration의 과거 계획

이 보고서 자체는 제거 근거를 설명하는 현재 migration note다. 세부 결과는
`benchmark-artifacts/phase8/audit/`에 보존했다.

## 롤백 계약 변경

Phase 8 이후 운영 롤백은 더 이상 `solver.engine` 변경이나 shadow engine switch로 수행할 수
없다. production 배포 전에 다음을 별도 계획하고 명시 승인해야 한다.

1. 이 Phase 8 commit 이전의 검증된 Git commit 또는 별도 image digest를 rollback 대상으로 고정
2. 배포 플랫폼별 이전 revision/image 전환 절차와 권한 확인
3. rollback smoke, API/Firestore snapshot, hard/pinned/integrity stop condition 고정
4. production image build·push·배포·traffic 전환·rollback rehearsal 각각 별도 승인

이 branch에서는 Git/image rollback 대상 생성, image push, 배포, 운영 전환을 수행하지 않았다.

## 남은 위험과 경계

- 로컬 Docker daemon이 없어 이미지의 최종 `docker load` 성공은 검증하지 못했다.
- production canary, latency/capacity, 운영 rollback rehearsal은 미실행이다.
- 잠긴 holdout은 승인되지 않아 실행하지 않았다.
- Phase 8 benchmark는 네 입력·seed 1개·고정 5,000 evaluations 범위다.
- 역사 문서와 기존 benchmark artifact에는 migration 증거로 이전 엔진 명칭이 의도적으로 남는다.

위 항목은 production 승인을 막는 사전 작업이지만, branch 내 제거 구현과 계약·점수·패키지
검증의 PASS를 뒤집는 구현 결함은 아니다.
