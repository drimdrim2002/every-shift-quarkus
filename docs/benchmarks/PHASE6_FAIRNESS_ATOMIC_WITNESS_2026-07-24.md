# Phase 6 fairness atomic witness 진단

- 작성일: 2026-07-24
- 상태: **test-only diagnostic/benchmark lane에서 존재 증명 완료, production 승격은 보류**
- 변경하지 않은 것: production 기본값, 점수 의미, ALNS operator, 사용자 합의 전 Phase 0 수치 gate

## 결론

`fairness.json`의 POJO Opta-style incumbent에는 hard와 soft[0]·soft[1]을 보존하면서
soft[2]를 엄격히 올리는 원자적 move가 실제로 존재한다. 이는 OptaPlanner 최종해의 assignment
diff를 복사한 결과가 아니다. seed 601/602의 POJO incumbent에서 모든 정의된 bounded neighborhood를
transaction으로 적용하고, 매 후보마다 full score를 다시 계산해 확인한 결과다.

두 seed 모두 가장 작은 witness는 길이 1의 재배정이며, 점수 변화는 동일하다.

```text
[0]hard/[0/0/-7628/0]soft -> [0]hard/[0/0/-7626/0]soft
delta = [hard=0, soft0=0, soft1=0, soft2=+2, soft3=0]
```

따라서 이전 100-candidate 제한 local-search run의 `accepted=0`은 “보호된 개선 경로가 없다”는
증거가 아니었다. 해당 샘플 예산에서 그 경로를 만나지 못한 것이다. 다만 이번의 701/702 smoke는
`fairness.json` 단일 입력·두 seed만 대상으로 하므로 production 활성화 또는 Phase 7 승격의
근거가 될 수 없다.

## 먼저 확인한 점수 계약

각 seed에서 같은 입력·같은 seed·명시적 예산으로 OptaPlanner와 POJO incumbent를 재현했다.
두 최종 assignment는 각각 다음 세 경로가 일치해야만 다음 단계로 진행했다.

1. POJO `RosterSolution.score()`
2. POJO `FullScoreCalculator`
3. OptaPlanner `SolutionManager`를 projection한 `BendableScore`

| seed | Opta/POJO score | Opta/POJO 재계산 | POJO↔Opta assignment diff edge |
|---:|---|---|---:|
| 601 | 둘 다 `[0]hard/[0/0/-7628/0]soft` | 모두 일치 | 270 |
| 602 | 둘 다 `[0]hard/[0/0/-7628/0]soft` | 모두 일치 | 214 |

따라서 witness는 score adapter나 점수 산식 차이로 생긴 것이 아니다. 진단 개발에는 601/602만
사용했으며, 이전 튜닝 seed 201/202·301/302와 잠긴 holdout 101..110은 사용하지 않았다.

## bounded exhaustive 탐색 공간과 완전성 경계

incumbent의 모든 mutable assignment를 기준으로 다음 후보를 생성했다. 동일한 최종 assignment를
두 generator가 만들면 canonical `shiftIndex -> newEmployee` key로 한 번만 평가했다. 이 중복 제거
뒤의 개수가 아래 candidate 수다.

| neighborhood | 완전 열거 범위 |
|---|---|
| 1-reassign | 모든 mutable shift × 현재 담당자가 아닌 모든 employee |
| 2-swap | incumbent 담당자가 다른 모든 mutable shift pair |
| 같은 실제일 3-cycle | 같은 날짜의 mutable shift 중 incumbent employee가 모두 다른 모든 3개와 두 방향 cycle |
| shift-type 4일 window 3/4-cycle | 같은 shift type, 시작일 포함 4일 이내 window의 mutable shift 중 incumbent employee가 모두 다른 모든 3/4개와 가능한 cycle permutation |

이는 **위 표의 bounded neighborhood 안에서는 완전**하다. 임의의 날짜를 넘는 5개 이상 cycle,
4일보다 긴 window, 보호 조건을 일시적으로 깨고 나중에 회복하는 다단계 path는 이번 부재/존재
판정의 대상이 아니다. 그러나 최소 witness가 1-reassign이므로, 더 큰 move를 추측으로 추가할
필요는 없었다.

모든 후보는 다음 계약을 지켰다.

```text
MoveTransaction.apply
-> IncrementalScoreCalculator.verifyAgainstFull
-> witness predicate 판정
-> rollback

candidate.hard    >= incumbent.hard
candidate.soft[0] >= incumbent.soft[0]
candidate.soft[1] >= incumbent.soft[1]
candidate.soft[2] >  incumbent.soft[2]
# soft[3]은 판정에서 제외
```

| seed | reassign | swap | day 3-cycle | type-window 3-cycle | type-window 4-cycle | 고유 후보 | 열거 시간 | rollback / score mismatch |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 601 | 5,400 | 42,615 | 6,552 | 26,656 | 218,556 | 299,779 | 105,573 ms | 0 / 0 |
| 602 | 5,400 | 42,613 | 6,552 | 26,662 | 218,388 | 299,615 | 108,886 ms | 0 / 0 |

## 최소 witness

| seed | shift | 날짜/type | 이전 employee | 이후 employee | delta |
|---:|---:|---|---|---|---|
| 601 | planning ID 185 (index 184) | 2026-05-20 / D | `96f5997d-b0a6-43eb-aa07-f14d1fe75308` | `6776b499-62a7-4c5a-acca-3cd8142219b2` | `[0,0,0,+2,0]` |
| 602 | planning ID 22 (index 21) | 2026-05-03 / D | `581e315f-aaa6-4cec-9414-250150b3d1fa` | `7ad4f4e2-bbfb-448a-b9a0-7205ce683673` | `[0,0,0,+2,0]` |

둘 다 cardinality 1, 날짜 폭 0일이므로 이번 열거에서 가능한 최소 크기·최소 변경 폭이다.

## 구현과 테스트

`FairnessProtectedReassignMove`는 test source에만 존재한다. 특정 witness의 employee/date를
내장하지 않고, 현재 state의 모든 mutable shift와 다른 employee를 안정된 순서로 생성한다.
full-score 검증 뒤 보호 조건을 통과한 후보만 witness다. production selector에는 연결하지 않았다.

| 파일 | 검증 내용 |
|---|---|
| `src/test/java/org/acme/solver/benchmark/FairnessProtectedReassignMove.java` | 일반화된 test-only 재배정 후보와 보호 predicate |
| `src/test/java/org/acme/solver/benchmark/FairnessProtectedReassignMoveTest.java` | 작은 3-shift fixture, 결정론적 후보열, transaction/full-score, rollback 뒤 assignment·score 복구 |
| `src/test/java/org/acme/solver/benchmark/FairnessAtomicWitnessDiagnosticTest.java` | incumbent/Opta 재현, score contract, diff graph, bounded exhaustive evidence artifact |

## 701/702 분리 검증과 Opta paired smoke

witness가 발견된 뒤에만 새로운 분리 seed 701/702를 사용했다. warm-up은 실행했지만 집계에서
제외했다. 동일 case의 실행 순서는 Opta/POJO가 번갈아 먼저 오도록 교차했고, Opta는 각 case에서
한 번만 실행했다. fixed-evaluation과 wall-clock을 섞어 해석하지 않았다.

- fixed: Opta `50,000 score calculation`, POJO `50,000 full-verified candidate`
- wall-clock: 각 엔진 10초
- 고정 평가의 단위가 서로 다르므로 throughput 비교가 아니다.

| profile | feasible Opta/POJO | W/T/L | POJO score p10 / median / p90 | paired delta p10 / median / p90 | best 도달 eval p50/p90 | p95 ms Opta/POJO | rollback / mismatch / corruption |
|---|---:|---:|---|---|---:|---:|---:|
| fixed-evaluations | 2/2 | 2/0/0 | `[0;0/0/-7624/0]` / `[0;0/0/-7624/0]` / `[0;0/0/-7624/0]` | `[0,0,0,+4,0]` / `[0,0,0,+4,0]` / `[0,0,0,+4,0]` | 20,078 / 24,007 | 1,178 / 18,485 | 0 / 0 / 0 |
| wall-clock | 2/2 | 2/0/0 | `[0;0/0/-7626/0]` / `[0;0/0/-7626/0]` / `[0;0/0/-7624/0]` | `[0,0,0,+2,0]` / `[0,0,0,+2,0]` / `[0,0,0,+4,0]` | 11,208 / 20,078 | 9,999 / 10,000 | 0 / 0 / 0 |

데이터셋별 W/T/L은 유일한 입력인 `fairness.json`에서 fixed `2/0/0`, wall-clock `2/0/0`이다.
네 pair 합계도 `4/0/0`이며 모두 feasible다.

선택 분포와 unique final-best 기여는 다음과 같다.

| profile | 선택 Change / Swap | accepted Change / Swap | final-best를 만든 마지막 move |
|---|---:|---:|---:|
| fixed-evaluations | 51,455 / 48,545 | 6 / 2 | Change 2, Swap 0 |
| wall-clock | 24,142 / 22,904 | 5 / 2 | Change 1, Swap 1 |

즉 새 witness의 직접적인 형태는 reassign이지만, 분리 smoke의 두 wall-clock run 중 하나에서는
중간/최종 보호 개선을 swap이 만들었다. 이 수치는 선택 규칙과 예산에 민감한 작은 관측이므로
operator 승격 근거가 아니다.

## artifact와 재현

| 목적 | artifact |
|---|---|
| seed 601 bounded exhaustive | `benchmark-artifacts/phase6/diagnostics/fairness-atomic/20260724T-dev601/summary.json`, `report.md` |
| seed 602 bounded exhaustive | `benchmark-artifacts/phase6/diagnostics/fairness-atomic/20260724T-dev602/summary.json`, `report.md` |
| 701/702 paired smoke | `benchmark-artifacts/phase6/diagnostics/fairness-atomic/20260724T-validation701-702/raw.jsonl`, `summary.json`, `report.md` |

```bash
cd /Users/brown/.codex/worktrees/f794/every-shift-quarkus

./mvnw -Dtest=FairnessAtomicWitnessDiagnosticTest \
  -Dfairness.atomic.diagnostic.enabled=true \
  -Dfairness.atomic.diagnostic.seeds=601,602 \
  -Dfairness.atomic.diagnostic.opta-evaluations=150000 \
  -Dfairness.atomic.diagnostic.pojo-evaluations=100000 \
  -Dfairness.atomic.diagnostic.output=benchmark-artifacts/phase6/diagnostics/fairness-atomic/replay-$(date -u +%Y%m%dT%H%M%SZ) \
  test

PARTITION=research-fairness-atomic-validation DATASETS=fairness.json SEEDS=701,702 \
REPEATS=1 WALL_CLOCK_SECONDS=10 OPTAPLANNER_EVALUATION_LIMIT=50000 \
POJO_EVALUATION_LIMIT=50000 WARMUP=true \
CANDIDATE_ENGINE=POJO_FAIRNESS_RESTRICTED_CHANGE_SWAP \
./scripts/benchmark/run-phase6-benchmark.sh quick
```

## 판정과 Phase 7

| 후보 | 판정 | 이유 |
|---|---|---|
| 보호된 1-reassign witness | test-only 유지 | 두 독립 development seed에서 존재를 재현했고 안전성 실패가 0이지만, production selector에는 미연결 |
| fairness restricted Change/Swap | 보류 | 701/702 smoke는 우세했지만 단일 입력·2 seed이며 p95는 fixed profile에서 훨씬 큼 |
| 새 ALNS operator / hyperparameter | 미구현 | bounded evidence가 이미 작은 재배정을 보였으므로 추측 추가가 불필요 |

Phase 7 승격은 아직 불가하다. 사용자가 결정해야 할 Phase 0 gate(운영 입력 전체에서의 paired loss,
feasible 비율, 상위 soft 열화 허용치, p95 한계, rollback/mismatch 허용치)가 정해지지 않았다.
다음 합당한 단계는 이 test-only selector를 production 기본값에 연결하는 것이 아니라, 합의된 gate와
새 holdout partition에서 재현하는 것이다.
