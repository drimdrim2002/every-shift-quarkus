# Phase 6 순차 hybrid·형평성 제한 local search 연구

- 작성일: 2026-07-24
- 상태: 테스트 전용 후보 구현·소규모 분리 측정 완료, 모두 **보류**
- production 기본값/점수 의미/Phase 0 수치 gate: 변경 없음

## 결론

기존 SA/ALNS 튜닝 결과가 보여 준 `fairness.json`의 soft[2] `-2/-4` 열세를 대상으로,
다음 두 구조를 구현했다.

1. `POJO_HYBRID_OPTA_THEN_ALNS`와 역순 `POJO_HYBRID_ALNS_THEN_OPTA`
2. `POJO_FAIRNESS_RESTRICTED_CHANGE_SWAP`

두 순차 hybrid는 401/402 개발 partition의 8 fixed-evaluation pair에서 각각 `2/3/3`으로
OptaPlanner를 이기지 못했고, `fairness.json`에서는 모두 `0/0/2`였다. 두 단계가 같은
warm start를 실제로 벗어난 경우는 preceptor의 feasibility bootstrap뿐이며, 이미 feasible한
fairness/request/sample에서는 50+50 complete-candidate 예산 안에 어떤 단계도 새 global
best를 만들지 못했다.

형평성 제한 후보는 501/502 분리 검증의 8 pair에서 `2/2/4`, `fairness.json` `0/0/2`였다.
800개의 full-verified Change/Swap 후보가 모두 거절되어 unique final-best 기여는 0이다.
따라서 이 후보는 안전성 문제는 없지만 현재 move topology로 알려진 basin을 탈출하지 못한다.

수치 승격 gate는 사용자 합의 전이므로, 이 문서의 수치는 어떤 승격이나 production 활성화의
근거가 아니다.

## 점수기 및 기존 실패의 재확인

후보 구현 전에 다음 기존 진단을 재사용했다.

- `FairnessHoldoutDiagnosticTest`는 seed 101의 최종해에서 POJO `FullScoreCalculator`와
  OptaPlanner `SolutionManager`가 각각 자신의 점수와 일치함을 확인한다.
- 대표 차이는 soft[2]만 `-7626` 대 `-7628`이고, 주간/저녁 제곱 부담 `5866` 대 `5868`이다.
- 따라서 새 후보는 score 산식이나 score 비교를 바꾸지 않았으며, 모든 commit 전 full score를
  다시 계산한다.

이 판단은 `OPTAPLANNER_SEARCH_ANALYSIS_2026-07-23.md`의 Change/Swap + late acceptance
분석과 `ALNS_NURSE_ROSTERING_OPERATOR_RESEARCH_2026-07-24.md`의 제곱 부담 hotspot 분석을
함께 따른 것이다. 기존 fairness hotspot pair는 final-best 기여가 불안정했으므로 그대로
기본값으로 승격하지 않았다.

## 구현과 계약

### 순차 hybrid

`SequentialHybridSolverEngine`은 두 엔진을 단순 직렬 호출하지 않는다.

- root warm start는 한 번 full-score로 검증한다. 두 order는 동일한 root solution과 외부 seed를
  사용한다.
- 각 stage는 독립 `SearchState`, incremental cache, transaction을 만들고, 이전 stage의
  **full-score 검증 immutable snapshot**만 다음 stage의 warm start로 받는다.
- 각 엔진은 이름 기반 파생 seed를 받는다. 따라서 같은 root seed에서 order가 달라도 같은
  stage 엔진의 RNG stream은 보존된다.
- fixed profile의 `maxEvaluations`는 총량을 올림 반분한다. 이번 run은 정확히 `50 + 50 = 100`
  complete-candidate evaluation이며, stage별 예산·실제 평가·시간·입출력 score가
  `research_metrics.stages`에 남는다.
- parent listener는 root와 전역 strict-best만 통지한다. stage 초기 callback이나 동점 callback이
  중복되지 않으며 parent `evaluationCount`는 stage evaluation의 합이다.
- score mismatch/state corruption은 즉시 중단하고 마지막 verified best만 반환한다.

wall-clock에서는 공통 initial/feasibility 준비 시간이 짧은 연구 예산을 잠식했다. 특히
preceptor는 feasible root를 만들기 위한 bootstrap이 커서 2초 smoke profile에서 첫 stage가
0 evaluation으로 deadline에 도달했다. 이는 좋은 결과가 아니라 시간 계약의 병목이므로,
알고리즘 품질은 fixed-evaluation profile로만 해석했다. wall-clock 재측정은 root warm-start
생성 예산을 별도로 고정한 뒤 수행해야 한다.

### 형평성 제한 intensification

`FairnessRestrictedLocalSearchEngine`은 Change/Swap만 선택한다. candidate는 transaction으로
증분 점수를 갱신한 뒤 매번 full-score 대조를 통과해야 하며, 다음 조건일 때만 commit한다.

```text
candidate.hard    >= current.hard
candidate.soft[0] >= current.soft[0]
candidate.soft[1] >= current.soft[1]
candidate.soft[2] >  current.soft[2]
```

soft[3]은 tie-break나 보상으로 사용하지 않는다. 따라서 higher-priority 목적식을 희생해
형평성을 바꾸는 경로는 구조적으로 차단된다. 입력 initial solution이 infeasible인 경우에는
기존 `LahcSolverEngine` feasibility bootstrap을 먼저 사용하고, 그 evaluation 수는
`initialFeasibilityEvaluations`로 분리 기록한다. 제한 local search 자체의 evaluation과
best 기여에는 bootstrap을 섞지 않는다.

relation-atomic move는 기존 `RelationGroupReassignMove`/`RelationGroupExchangeMove`가 이미
transaction 경계를 제공하지만, 이번 실패 pattern은 relation 제약이 아니라 동일 shift type의
부담 제곱이었다. 새 relation operator를 추측으로 추가하지 않고 Change/Swap 결과를 먼저
측정했다.

## 테스트

- `FairnessRestrictedLocalSearchEngineTest`
  - 3일·2직원 fixture에서 day burden 제곱을 `-9 → -5`로 개선한다.
  - hard/soft[0]/soft[1] 악화 후보는 soft[2]가 좋아도 거절한다.
  - full score, rollback failure 0을 확인한다.
- `SequentialHybridSolverEngineTest`
  - `5 → 3 + 2` 평가 예산, stage-2 verified warm start, listener 단조성을 확인한다.
- 기존 `MoveTransactionPropertyTest`, incremental/full differential test는 신규 엔진도
  재사용하는 transaction·점수 계약의 방어선이다.

## seed partition과 재현

`201/202`, `301/302`, 잠긴 holdout `101..110`은 사용하지 않았다.

| 목적 | datasets | seed | profile | artifact |
|---|---|---|---|---|
| hybrid 개발 | 4 운영 입력 | 401, 402 | fixed, Opta 10,000 / POJO 100 | `benchmark-artifacts/phase6/research/hybrid-opta-then-alns-fixed-dev-20260724/`, `.../hybrid-alns-then-opta-fixed-dev-20260724/` |
| fairness 분리 검증 | 4 운영 입력 | 501, 502 | fixed, Opta 10,000 / POJO 100 | `benchmark-artifacts/phase6/research/fairness-restricted-fixed-validation-20260724/` |

고정 profile의 OptaPlanner 단위는 score calculation, POJO 단위는 complete candidate이므로
throughput 비교가 아니다. warm-up은 이 소규모 run에서 제외했다. Opta/후보 순서는 harness가
case index마다 교차하며, 같은 case의 Opta 결과는 candidate별 run 내부에서 한 번만 실행된다.

재현 명령:

```bash
cd /Users/brown/.codex/worktrees/f794/every-shift-quarkus

PARTITION=research-hybrid-dev DATASETS=fairness.json,preceptor.json,request.json,sample.json \
SEEDS=401,402 REPEATS=1 WARMUP=false OPTAPLANNER_EVALUATION_LIMIT=10000 \
POJO_EVALUATION_LIMIT=100 HYBRID_ORDER=opta-then-alns \
./scripts/benchmark/run-phase6-hybrid-research-benchmark.sh fixed

PARTITION=research-fairness-validation DATASETS=fairness.json,preceptor.json,request.json,sample.json \
SEEDS=501,502 REPEATS=1 WARMUP=false OPTAPLANNER_EVALUATION_LIMIT=10000 \
POJO_EVALUATION_LIMIT=100 \
./scripts/benchmark/run-phase6-fairness-restricted-benchmark.sh fixed
```

## 관측 결과

점수 표기는 `[hard;soft0/soft1/soft2/soft3]`, 분위수는 기존 harness의 사전식
nearest-rank이며 paired delta는 `[hard, soft0, soft1, soft2, soft3]` 좌표다.

| 후보 | partition | feasible Opta/후보 | W/T/L | 후보 p10 / median / p90 | paired delta p10 / median / p90 | best eval p50/p90 | p95 ms Opta/후보 | rollback failure / mismatch |
|---|---|---:|---:|---|---|---:|---:|---:|
| Opta→ALNS | 개발 401/402 | 6/8 | 2/3/3 | `[0;-480/-6720/-7343/0]` / `[0;0/0/-7632/0]` / `[0;0/0/-5283/0]` | `[0,-480,0,-2,0]` / `[0,0,0,0,0]` / `[8,0,6240,1992,0]` | 100/100 | 503/4000 | 0/0 |
| ALNS→Opta | 개발 401/402 | 6/8 | 2/3/3 | `[0;-960/-5280/-7055/0]` / `[0;0/0/-7632/0]` / `[0;0/0/-5283/0]` | `[0,-960,0,-2,0]` / `[0,0,0,0,0]` / `[8,0,6240,2332,0]` | 100/100 | 495/5106 | 0/0 |
| fairness restricted | 검증 501/502 | 6/8 | 2/2/4 | `[0;0/-7200/-7043/0]` / `[0;0/0/-7632/0]` / `[0;0/0/-5283/0]` | `[0,0,0,-2,0]` / `[0,0,0,-2,0]` / `[8,1440,4800,2322,0]` | 0/0 | 489/3663 | 0/0 |

데이터셋별 W/T/L은 다음과 같다.

| 후보 | fairness | preceptor | request | sample |
|---|---:|---:|---:|---:|
| Opta→ALNS | 0/0/2 | 2/0/0 | 0/1/1 | 0/2/0 |
| ALNS→Opta | 0/0/2 | 2/0/0 | 0/1/1 | 0/2/0 |
| fairness restricted | 0/0/2 | 2/0/0 | 0/0/2 | 0/2/0 |

형평성 제한 검증의 선택 분포는 Change 416, Swap 384였다. accepted Change/Swap은 각각
0/0, final-best 기여도 0이다. 이는 warm start `-7632`가 100개 local move 안에 실제로
깨지지 않았다는 직접 증거다. hybrid의 fairness 두 case도 각 stage `50 → 50`에서
`-7632 → -7632 → -7632`였고 unique final-best 기여가 없었다.

## 판정과 다음 병목

| 후보 | 판정 | 근거 |
|---|---|---|
| Opta→ALNS | 보류 | fairness 전패, second stage unique best 0, p95 증가 |
| ALNS→Opta | 보류 | 같은 W/T/L, fairness 전패, p95가 더 큼 |
| fairness restricted Change/Swap | 보류 | 검증에서 800/800 reject, soft2 best 0 |

다음 구조적 병목은 개별 Change/Swap에 top-level-preserving soft2 개선 경로가 없다는 점이다.
다음 연구 후보는 하루/shift-type 단위의 **동시 다중 교환** 또는 현재 final solution에서
soft2 plateau를 유지하는 다단계 path를 별도 fixture로 증명하는 것이다. 이는 새 operator를
즉시 기본 활성화한다는 뜻이 아니며, relation-atomic move가 필요한 preceptor fixture와
day/evening 부담 fixture를 분리해 먼저 transaction·score contract를 검증해야 한다.

Phase 7 promotion은 아직 불가하다. 사용자에게 남은 결정은 기존 Phase 0 gate의 paired loss,
dataset veto, feasible 비율, p95/rollback 허용치다.
