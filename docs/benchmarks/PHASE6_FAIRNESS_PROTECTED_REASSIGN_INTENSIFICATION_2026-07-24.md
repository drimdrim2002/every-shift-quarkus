# Phase 6 fairness-hotspot guided protected-reassign intensification

- 작성일: 2026-07-24
- 상태: **test-only candidate 구현·분리 검증 완료, production 보류**
- 변경하지 않은 것: production 기본값, 점수 의미, ALNS 기본 operator, Phase 0 수치 gate, 커밋·배포

## 결론

이전 bounded exhaustive 진단이 보인 protected single reassign witness를, 모든 후보를 full score로
훑지 않고 실제로 우선 발견하는 selector로 구현했다. `fairness.json` 801/802에서는 후보 5,400개를
전부 full-score 평가하지 않고 각 run에서 24개만 검증하여 soft[2]를 `-7628 → -7626`으로 올렸다.
hard, soft[0], soft[1]은 유지했고 rollback/score mismatch/state corruption은 0이다.

그러나 이 선택기는 fairness만 겨냥한다. 분리 검증에서 `request.json`과 `sample.json`은 Opta보다
soft[2]가 각각 2점 낮은 결과가 재현됐다. 또한 5초/50,000 평가의 짧은 Opta 기준은
`preceptor.json` feasible 해를 만들지 못했으므로, 그 입력에서 후보가 이긴 것은 selector의
품질 우위로 해석할 수 없다. 따라서 후보는 **test-only 보류**이며 Phase 7 승격이나 기본 활성화
근거가 아니다.

## 왜 random 800이 witness를 놓쳤는가

이전 `FairnessRestrictedLocalSearchEngine`의 800회 샘플은 Change 416회, Swap 384회였다.
`fairness.json` incumbent의 단일 재배정 공간은 `300 × 18 = 5,400`개다.

- 특정 단일 witness 하나를 416개 균등 Change에서 만날 확률은
  `1 - (5,399 / 5,400)^416 = 7.42%`다.
- 800회가 모두 Change였어도 같은 특정 witness를 만날 확률은 13.77%에 불과하다.
- Swap 384회는 single-reassign witness 발견에는 직접 기여하지 않는다.

따라서 800회의 `accepted=0`은 개선 경로의 부재가 아니라 낮은 reassign 피복률과 Change/Swap
family 혼합의 결과다. 이번 selector는 random seed로 후보 우선순위를 흔들지 않는다.

## 설계: 힌트와 수락 계약의 분리

### 저비용 prefilter와 hotspot 순위

현재 complete state에서 직원별 다음 fairness 집계를 한 번 계산한다.

- 야간 burden 합의 제곱
- 휴일 burden 합의 제곱
- 주간 count 제곱과 저녁 count 제곱×5

한 재배정의 old/new 직원 두 명에 대해서만 위 제곱 변화량을 계산해 `fairnessDeltaHint`로 쓴다.
이는 `FairnessConstraint`와 같은 산식의 **soft[2] 정렬 힌트**일 뿐, 점수나 수락 여부를
추측하는 incremental evaluator가 아니다. 다음 후보만 queue에 넣는다.

1. mutable shift이며 target employee가 현재 담당자가 아님
2. target이 해당 shift code와 required skill을 보유함
3. `fairnessDeltaHint > 0`

각 state에서 최대 256개만 full-score 검증 대상으로 내보낸다. 동일 hint의 결정론 tie-break는
다음 순서다.

1. `fairnessDeltaHint` 내림차순
2. source employee fairness penalty 내림차순
3. target employee fairness penalty 오름차순
4. shift index 오름차순
5. target employee index 오름차순

### 상위 목적식 보호와 최종 검증

힌트/eligibility는 hard·soft[0]·soft[1]을 증명하지 않는다. 모든 emitted candidate는 기존
`MoveTransaction`으로 적용한 뒤 `IncrementalScoreCalculator.verifyAgainstFull`을 통과해야 하며,
아래 조건이 참일 때만 commit한다.

```text
candidate.hard    >= current.hard
candidate.soft[0] >= current.soft[0]
candidate.soft[1] >= current.soft[1]
candidate.soft[2] >  current.soft[2]
# soft[3]은 판정하지 않음
```

reject·예외·취소는 rollback하고 fingerprint/score cache를 검증한다. 이 구현은 새로운 점수 산식이나
추측성 incremental acceptance를 도입하지 않았다. 601/602 회귀에서는 final solution을 다시
Opta `SolutionManager`로 계산해 POJO full score와의 일치도 확인했다.

## 구현과 테스트

| 파일 | 내용 |
|---|---|
| `src/main/java/org/acme/solver/lahc/FairnessHotspotGuidedProtectedReassignSelector.java` | fairness 제곱 delta 힌트·eligibility prefilter·안정 tie-break·256개 bounded queue |
| `src/main/java/org/acme/solver/lahc/FairnessSelectorMetrics.java` | ranking/raw/eligibility/hint/emitted 관측값 |
| `src/main/java/org/acme/solver/lahc/FairnessRestrictedLocalSearchEngine.java` | test-only static factory와 selector metric 전달. 기본 selector는 변경 없음 |
| `src/test/java/org/acme/solver/lahc/FairnessHotspotGuidedProtectedReassignSelectorTest.java` | 작은 fixture의 순위·tie-break, full-score 검증, rollback, seed-독립 결정론 |
| `src/test/java/org/acme/solver/lahc/FairnessHotspotGuidedProtectedReassignRegressionTest.java` | seed 601/602 incumbent에서 256개 안에 witness 발견, upper-level 보존, Opta SolutionManager 교차검증 |
| `src/test/java/org/acme/solver/benchmark/Phase6PairedBenchmarkTest.java` | `POJO_FAIRNESS_HOTSPOT_PROTECTED_REASSIGN` test-only benchmark candidate |

601/602 회귀는 2/2 통과했다. 두 seed 모두 100,000-evaluation Opta-style incumbent에서 시작해
256개 이하 guided 후보로 soft[2] 개선을 찾았고, full/Opta 재점수·rollback/mismatch/corruption은
모두 0이었다.

## pilot과 분리 검증

### pilot

pilot은 `fairness/preceptor/request/sample` × seed 801, 3초 wall-clock과 256 fixed candidate로
먼저 안전 veto를 확인했다. 후보 쪽 infeasible, 상위 목적식 손실, rollback/mismatch는 없었다.
다만 request에서 soft[2] `-5281` 대 Opta `-5279`로 2점 뒤졌고, 이 신호를 이유로 후보를
승격하지 않고 확장 검증에서도 별도 기록했다.

### 분리 검증: 801/802

- 입력: `fairness.json`, `preceptor.json`, `request.json`, `sample.json`
- seed: 801, 802 (이전 201/202, 301/302, 601/602, 701/702, 잠긴 101..110과 분리)
- warm-up: 실행, 집계 제외
- 실행 순서: case별 Opta-first/POJO-first 교차
- fixed: Opta 50,000 score calculation / POJO 최대 5,000 full-verified candidate
- wall-clock: 각 엔진 5초
- 두 평가 단위가 다르므로 fixed 표는 throughput 비교가 아니다.

| profile | feasible Opta/POJO | 전체 W/T/L | POJO 사전식 score p10 / median / p90 | paired delta p10 / median / p90 | soft[2] delta p10 / median / p90 | best eval p50/p90 | p95 ms Opta/POJO | rollback/mismatch |
|---|---:|---:|---|---|---|---:|---:|---:|
| fixed-evaluations | 6/8 | 4/2/2 | `[0;0/-6240/-7261/0]` / `[0;0/0/-7626/0]` / `[0;0/0/-5281/0]` | `[0,0,0,-422,0]` / `[0,0,0,-2,0]` / `[5,5280,8160,+2,0]` | `-422 / -2 / +2` | 0 / 17 | 1,298 / 3,686 | 0 / 0 |
| wall-clock | 6/8 | 3/1/4 | `[0;0/-6240/-7261/0]` / `[0;0/0/-7626/0]` / `[0;0/0/-5281/0]` | `[0,0,0,-1052,0]` / `[0,0,0,-2,0]` / `[1,1920,4800,+2,0]` | `-1052 / -2 / +2` | 0 / 17 | 5,000 / 3,771 | 0 / 0 |

`preceptor.json`의 두 pair에서 Opta가 이 짧은 예산 안에 infeasible이어서 POJO가 사전식 승리했다.
이 두 승리는 candidate 품질의 증거로 세지 않는다. 반대로 fairness의 두 fixed pair 승리는
보호 reassign이 실제로 soft[2]를 +2 올린 결과다.

| profile | fairness | preceptor | request | sample |
|---|---:|---:|---:|---:|
| fixed W/T/L | 2/0/0 | 2/0/0* | 0/2/0 | 0/0/2 |
| wall W/T/L | 1/1/0 | 2/0/0* | 0/0/2 | 0/0/2 |

`*` Opta infeasible pair이므로 승격 판단에서 제외해야 한다.

### selector 비용과 기여

8개 run씩인 각 profile의 selector 합계는 다음과 같다. raw 수는 accepted move 뒤 ranking을 다시
만든 횟수까지 포함하며, emitted만 full-score 후보 평가 수다.

| profile | raw reassign | eligibility 통과 | fairness hint 양수 | full 검증 emitted | accepted | final-best 기여 |
|---|---:|---:|---:|---:|---:|---:|
| fixed | 86,616 | 86,616 | 5,280 | 568 | 8 | Reassign 4 / 없음 4 |
| wall-clock | 86,616 | 86,616 | 5,280 | 568 | 8 | Reassign 4 / 없음 4 |

fixed와 wall의 실제 candidate 소비는 동일했다. fairness는 각 run 24개 중 3개를 accept했고 마지막
best는 eval 17의 Reassign이었다. request는 4개 중 1개를 accept했지만 Opta 기준보다 soft[2]
2점 낮았고, sample은 positive fairness hint가 0개라 즉시 converge했다. preceptor는 2,373/2,689개의
positive hint가 있었지만 상위 목적식을 보호하는 candidate가 256개 안에는 없어 모두 reject됐다.

## artifact와 재현

- pilot: `benchmark-artifacts/phase6/diagnostics/fairness-protected-reassign/20260724T-pilot801/`
- 분리 검증 raw/summary/report:
  `benchmark-artifacts/phase6/diagnostics/fairness-protected-reassign/20260724T-validation801-802/`

```bash
cd /Users/brown/.codex/worktrees/f794/every-shift-quarkus

./mvnw -Dtest=FairnessHotspotGuidedProtectedReassignRegressionTest \
  -Dfairness.hotspot.regression.enabled=true test

PARTITION=research-fairness-hotspot-validation \
DATASETS=fairness.json,preceptor.json,request.json,sample.json SEEDS=801,802 \
REPEATS=1 WALL_CLOCK_SECONDS=5 OPTAPLANNER_EVALUATION_LIMIT=50000 \
POJO_EVALUATION_LIMIT=5000 WARMUP=true \
CANDIDATE_ENGINE=POJO_FAIRNESS_HOTSPOT_PROTECTED_REASSIGN \
OUTPUT_DIR=benchmark-artifacts/phase6/diagnostics/fairness-protected-reassign/replay-$(date -u +%Y%m%dT%H%M%SZ) \
./scripts/benchmark/run-phase6-benchmark.sh quick
```

80-pair 정규 holdout은 이 작업에서 실행하지 않았다. Phase 0 gate가 미합의이고 5초/50,000 pilot에서
request/sample soft[2] 손실이 이미 재현됐기 때문이다. gate 합의 후 실행할 정확한 명령과 예상
artifact 위치는 다음과 같다.

```bash
cd /Users/brown/.codex/worktrees/f794/every-shift-quarkus

PARTITION=fairness-hotspot-protected-holdout \
DATASETS=fairness.json,preceptor.json,request.json,sample.json \
SEEDS=101,102,103,104,105,106,107,108,109,110 REPEATS=2 \
WALL_CLOCK_SECONDS=60 WARMUP=true \
CANDIDATE_ENGINE=POJO_FAIRNESS_HOTSPOT_PROTECTED_REASSIGN \
OUTPUT_DIR=benchmark-artifacts/phase6/fairness-hotspot-protected/holdout/wall-clock \
./scripts/benchmark/run-phase6-benchmark.sh wall-clock

PARTITION=fairness-hotspot-protected-holdout \
DATASETS=fairness.json,preceptor.json,request.json,sample.json \
SEEDS=101,102,103,104,105,106,107,108,109,110 REPEATS=2 \
OPTAPLANNER_EVALUATION_LIMIT=50000 POJO_EVALUATION_LIMIT=5000 WARMUP=true \
CANDIDATE_ENGINE=POJO_FAIRNESS_HOTSPOT_PROTECTED_REASSIGN \
OUTPUT_DIR=benchmark-artifacts/phase6/fairness-hotspot-protected/holdout/fixed \
./scripts/benchmark/run-phase6-benchmark.sh fixed
```

각 실행은 `raw.jsonl`, `summary.json`, `report.md`를 위 `OUTPUT_DIR`에 남긴다. 두 holdout 명령의
예산은 이번 후보의 품질 gate가 아니라 재현 profile의 명시값이며, 승인 전에는 사용자 gate에 맞춰
다시 확정해야 한다.

## 판정과 다음 의사결정

| 대상 | 판정 | 근거 |
|---|---|---|
| hotspot guided protected reassign | test-only 보류 | fairness witness를 24 full verification으로 발견하지만 request/sample soft[2] 손실 재현 |
| 256-candidate bound | test-only 유지 | 601/602 회귀를 통과했으나 preceptor positive hint 2천여 개 중 상위 보호 후보는 찾지 못함 |
| 새 ALNS operator/hyperparameter | 미추가 | 현재 증거는 단일 reassign 선택의 효과·한계만 보이며 추측 확장을 정당화하지 않음 |
| Phase 7 / production default | 불가 | Phase 0 gate 미합의, 4 입력 공통 비열화 없음 증거 부재, short-budget Opta infeasibility 교란 존재 |

다음 사용자 결정은 허용 paired loss, 데이터셋 veto(특히 request/sample soft[2]), feasible 요구,
p95 상한, rollback/mismatch 허용치다. 그 결정을 받기 전에는 selector 범위를 넓히거나 기본값에
연결하지 않는다.
