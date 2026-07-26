# OptaPlanner 탐색 구조 분석과 POJO_ALNS 보완 방향

작성일: 2026-07-23  
대상 버전: `org.optaplanner:optaplanner-core-impl:10.0.0`  
범위: 이 저장소의 `OptaPlannerSolverEngine`이 실제 생성하는 기본 solver와
`POJO_ALNS` baseline-only의 탐색 행동 비교. 점수 의미, production 기본 엔진,
operator 기본 활성 상태는 이 문서만으로 변경하지 않는다.

## 결론

이 프로젝트의 OptaPlanner는 여러 해를 population처럼 합성하거나 crossover하는
알고리즘이 아니다. 하나의 working solution을 유지하면서 다음의 두 단계를 수행한다.

1. 아직 `employee == null`인 mutable `Shift`를 순서대로 채우는 construction heuristic
2. 완성된 해에서 **단일 배정 변경(Change)** 또는 **두 배정 교환(Swap)** 을 무작위로
   생성하고, 400-step Late Acceptance로 수락 가능한 첫 move를 적용하는 local search

따라서 현재 POJO_ALNS의 핵심 결손은 후보 destroy/repair 수가 적다는 점보다,
정상 feasible 탐색에서 OptaPlanner가 사용하는 작은 정확 move 이웃이 없다는 점이다.
특히 preceptor relation group은 현재 `destroy -> 원래 직원으로 restore` 조합으로
되돌아가기 쉬워, 실제 slot 교환을 만들지 못한다.

named destroy/repair 후보를 추가하기 전에 **Opta-style exact local-move lane**을 baseline과
분리해 구현했다.
그 lane이 holdout에서 이득을 보인 뒤에만 `EmployeeWindowRemoval` 등 대형 destroy/repair를
그 위에 추가한다.

## 1. 분석 대상의 실제 solver 구성

`OptaPlannerSolverEngine`은 `SolverConfig`에 phase, move selector, acceptor를 직접
설정하지 않는다. solution class, entity class, constraint provider, termination,
move thread, reproducible mode, seed만 설정한다.

```text
EmployeeSchedule
  └─ Shift (planning entity)
       └─ employee (단일 planning variable, value range = employeeList)

SolverConfig에 phaseConfigList 없음
  └─ OptaPlanner 10.0.0 default phase 생성
       ├─ Construction Heuristic: ALLOCATE_ENTITY_FROM_QUEUE
       └─ Local Search: LATE_ACCEPTANCE
```

근거는 다음과 같다.

- `EmployeeSchedule`은 `Shift` collection과 `employeeList` value range를 갖는다.
- `Shift.employee`는 유일한 planning variable이며 pinning filter를 사용한다.
- `OptaPlannerSolverEngine#createSolver`는 phase configuration을 제공하지 않는다.
- OptaPlanner 10.0.0 `DefaultSolverFactory#buildPhaseList`는 phase가 비어 있으면 entity별
  construction phase 하나와 local-search phase 하나를 추가한다.

benchmark는 production과 의도적으로 다르게 `moveThreadCount=NONE`을 사용한다.
따라서 holdout의 OptaPlanner는 single-thread, fixed seed, reproducible local search이며,
`application.properties`의 production `AUTO` move thread 결과와 혼동하면 안 된다.

## 2. Construction Heuristic: 처음 해를 만드는 방식

### 2.1 입력 상태

요구량으로 생성된 미래 shift는 처음에 `employee == null`이다. historic shift는 pinned이고,
POJO initial builder도 이와 별도로 complete solution을 만든다. 즉 OptaPlanner에서는
construction phase 자체가 빈 미래 shift를 채우는 과정이고, POJO_ALNS benchmark에서는
`InitialSolutionBuilder`가 이미 complete feasible 해를 만든 뒤 ALNS가 시작한다.

### 2.2 실제 기본 동작

기본 construction type은 `ALLOCATE_ENTITY_FROM_QUEUE`다.

- `QueuedEntityPlacer`는 entity selector를 phase cache, original order로 만든다.
- 이 프로젝트에는 `Shift` entity와 `employee` planning variable 하나가 있으므로,
  queue의 각 mutable shift에 대해 Change move 후보를 만든다.
- pinned shift는 planning pinning filter 때문에 이동 대상이 아니다.
- 이 phase는 population을 만들지 않는다. 한 working solution에서 queue를 진행하면서
  한 shift의 `employee` value를 정한다.

초기 배정 품질을 그대로 복제하는 것은 목적이 아니다. POJO에는 별도 initial builder가 있다.
다만 이것이 complete assignment를 만들더라도, `preceptor.json`처럼 relation 제약을 모두
해소한 hard score 0 초기해를 항상 반환하는 것은 아니다. baseline ALNS는 이 경우
relation-aware `SeededMoveSelector`로 최대 10,000회 feasibility bootstrap을 수행한다.
새 local-move lane도 같은 bootstrap을 먼저 수행하고, 그 평가 횟수는 본 local move 평가와
분리해 artifact의 `initial_feasibility_evaluation_count`에 기록한다. 이 단계 뒤 hard score 0이
없으면 Change/Swap 탐색을 시작하지 않고 `NO_FEASIBLE_SOLUTION`으로 종료한다. 여기서 중요한
차이는 이후 local search의 이웃 구조다.

## 3. Local Search: OptaPlanner가 해를 바꾸는 방식

### 3.1 이웃(move) 구성

기본 local search move selector는 `JUST_IN_TIME + RANDOM`인 union selector다.
이 문제처럼 list variable이 아닌 일반 planning variable만 있으면 union의 자식은 둘이다.

```text
랜덤 move stream
  ├─ ChangeMove: Shift 하나의 employee를 다른 value로 변경
  └─ SwapMove: Shift 두 개의 employee value를 교환
```

핵심 특성은 다음과 같다.

- Change는 한 배정만 바꾼다.
- Swap은 두 shift를 원자적으로 교환한다. 한 직원을 새로 과적시키지 않고 부하·선호·휴식을
  재배치할 수 있다.
- value range의 모든 employee가 후보다. skill, 휴식, preceptor 관계는 move selector가
  미리 제거하는 제약이 아니라 score가 판정하는 제약이다.
- 따라서 hard score 0 상태에서 불법 move도 후보로는 생성되지만, 아래 acceptance 때문에
  보통 수락되지 않는다.
- full solution을 지우고 greedy로 다시 만들지 않는다. 작은 변경의 exact incremental score를
  바로 비교한다.

이것이 질문에서 말한 “해를 섞는” 실제 단위다. 전체 해 두 개를 섞는 것이 아니라,
현재 해 안에서 두 shift의 employee value를 swap하거나 하나를 change한다.

### 3.2 Late Acceptance의 정확한 규칙

기본 local search type은 `LATE_ACCEPTANCE`, 기본 history length는 **400**이다.

초기 history의 400칸은 모두 시작 score로 채워진다. step `t`에서 후보 `C`, 현재 score `S`,
ring buffer의 score `H[t mod 400]`에 대해 다음을 만족하면 후보가 수락된다.

```text
C >= H[t mod 400]  또는  C >= S
```

score 비교는 bendable score의 사전식 비교다. 즉 hard level이 먼저이고, 그 다음 soft0,
soft1, soft2, soft3 순서다.

수락된 step 뒤에는 그 step의 current score를 해당 history slot에 **항상 덮어쓴다**.
history slot의 최고 score만 보존하지 않는다. 이 차이 때문에 일시적으로 낮아진 지역도
400 step 전 score보다 충분히 좋으면 탐색할 수 있고, 이후 다시 회복할 수 있다.

### 3.3 후보를 고르는 방식

기본 forager는 `acceptedCountLimit=1`이다. 무작위 move를 score하고, 위 규칙을 만족하는
첫 후보를 찾으면 그 step의 탐색을 끝낸다. 따라서 기본 동작은 “무작위 후보를 대량 평가해
그중 최고를 선택”하는 best-of-N이 아니라 **수락 가능한 첫 local move**다.

```text
current solution S
  │
  ├─ random Change/Swap 후보 M1 → score → 거절: rollback
  ├─ random Change/Swap 후보 M2 → score → 거절: rollback
  └─ random Change/Swap 후보 Mk → Late Acceptance 통과
                                             │
                                             ├─ commit
                                             ├─ history[t % 400] = score(S')
                                             └─ S = S'
```

`moveThreadCount=NONE` benchmark에서는 이 흐름이 단일 thread에서 재현된다. production의
`AUTO` move thread는 candidate score 평가의 병렬성만 바꾸며, benchmark evidence와 같은
결정론적 trace라고 가정해서는 안 된다.

## 4. 현재 POJO_ALNS와의 구조적 차이

| 관점 | OptaPlanner 기본 | 현재 POJO_ALNS baseline |
|---|---|---|
| 시작 해 | null assignment를 construction phase가 채움 | `InitialSolutionBuilder`가 complete 해 생성 |
| 정상 탐색 이웃 | 단일 Change 또는 2-shift Swap | 5% 규모(최소 1, 최대 8, 상한 12)의 destroy 후 repair |
| 후보 생성 | exact score로 판단될 작은 move | repair의 지역 proxy insertion cost로 complete candidate 구성 |
| 후보 검증 | ScoreDirector의 exact incremental score | transaction incremental + 매 complete candidate full 검증 |
| 수락 | 400-step Late Acceptance | calibrated lexicographic simulated annealing |
| hard 0 이후 | history/current 모두 hard 0이면 hard 악화는 수락 불가 | `feasibleRegionLocked`가 hard 악화 후보를 즉시 거절 |
| preceptor 관계 | 일반 Change/Swap만 기본 제공 | relation bundle destroy + original restore, ALNS 본 탐색에는 relation exchange 없음 |

### 4.1 POJO repair proxy의 범위

`RepairContext#insertionCost`는 complete score가 아니다. 아래만 근사한다.

- 같은 날, overlap, 12시간 미만 rest의 큰 penalty
- assigned count의 작은 load penalty
- desired/undesired availability의 상수 bonus/penalty
- 같은 날짜·shift-code의 relation consistency

반면 실제 score의 핵심 일부가 proxy에는 없다.

- night 뒤 32시간 rest (soft0)
- 연속 night 뒤 48시간 recovery와 기타 hard night chain
- night/holiday/day-evening burden의 제곱형 fairness (soft2)
- 실제 desired constraint의 전체 의미

ALNS는 repair 뒤 완성 candidate를 full score로 검증하므로 score 의미가 바뀌지는 않는다.
다만 repair가 어떤 candidate를 만들지 결정하는 순간에는 이 차이 때문에 fairness/preceptor
tail에서 좋은 방향을 놓칠 수 있다.

### 4.2 relation group의 이동 경로가 막히는 문제

`PreceptorRelationGroupRemoval`은 같은 날짜·shift code의 relation group을 함께 제거한다.
그 destroy와 호환되는 repair는 `RelationAwareRepair` 하나뿐이다.

`RelationAwareRepair#restoreRemovedRelationBundles`는 제거된 complete relation bundle을
**원래 employee로 다시 배정**한다. relation bundle만 제거된 iteration이라면 candidate는
원래 assignment와 동일해지기 쉽다. 추가 shift도 제거된 경우에만 그 나머지가 greedy로 변할 수
있다.

따라서 이 pair는 “관계 group을 다른 slot으로 옮기거나 singleton slot과 교환”하는 탐색이
아니다. 이미 구현되어 있고 Phase 5 테스트로 검증된 `RelationGroupExchangeMove`가 바로 그
원자적 이동 모델이다. 하지만 이것은 initial feasibility bootstrap의 `SeededMoveSelector`에서만
사용되고, complete feasible 상태의 ALNS main loop에서는 사용되지 않는다.

이 결론은 operator 통계와도 부합한다. fixed-evaluation holdout에서
`PRECEPTOR_RELATION_GROUP_REMOVAL`은 666,160회 선택됐지만 final-best contribution은 0회였다.
같은 run에서 relation-aware repair는 1,201,494회 선택됐다. `accepted worsening`에는 equal
candidate도 포함되므로 이 수치만으로 no-op 횟수를 단정할 수는 없지만, source 수준의
original restore와 함께 봤을 때 exploration 효율 문제의 강한 신호다.

### 4.3 이미 있는 LAHC와 OptaPlanner default의 차이

`LahcSolverEngine`과 `SeededMoveSelector`는 중요한 자산이다. reassign, swap,
`RelationGroupExchangeMove`를 안전 transaction으로 적용한다. 그러나 OptaPlanner default의
직접 복제는 아니다.

| 항목 | OptaPlanner default | 현재 POJO_LAHC |
|---|---|---|
| history length | 400 | 기본 100 |
| history 갱신 | 수락 step score를 항상 덮어씀 | slot의 기존 score보다 좋아질 때만 갱신 |
| step 단위 | 수락 가능한 첫 move를 찾을 때까지 후보 scan | 선택된 move 하나마다 acceptance/evaluation 진행 |
| move set | Change + Swap | reassign 60%, swap 30%, relation exchange 10% 시도 |
| 사전 필터 | planning pin 외 value range candidate 생성 | relation singleton·shift code 조건을 미리 필터 |

따라서 LAHC를 그대로 ALNS의 근거로 삼거나, “이미 OptaPlanner를 모사한다”고 결론내리면 안 된다.
다만 transaction, exact move, relation exchange 구현은 새 local-move lane을 만들 때 재사용할 수 있다.

## 5. Holdout 결과가 말하는 것

현재 결과는 baseline-only ALNS가 안전하고 중앙값은 대체로 동률이지만, OptaPlanner보다
일관되게 좋다고 말할 수는 없음을 보인다.

| profile | 전체 W/T/L (POJO 대 Opta) | non-loss | 문제 구간 |
|---|---:|---:|---|
| wall-clock | 21 / 47 / 12 | 85% | fairness soft2 p10 -6, preceptor soft1/soft2 p10 -1920/-1294 |
| fixed-evaluation | 16 / 44 / 20 | 75% | fairness soft2 p10 -4, preceptor soft1/soft2 p10 -1920/-1294, sample soft2 p10 -2 |

모든 160 run은 feasible이고 hard score 0이었으며 score mismatch, rollback failure,
operator exception은 0이었다. 따라서 다음 우선순위는 안전성 복구가 아니라 **preceptor와
fairness의 move topology 및 candidate 생성 품질**이다.

fixed benchmark의 엔진별 evaluation 단위는 OptaPlanner가 score calculation,
POJO가 complete candidate이므로 throughput으로 직접 비교하지 않는다. 이 문서의 알고리즘
판단은 paired score와 failure pattern에만 근거한다.

## 6. 권장 구현: Opta-style exact local-move lane

### 6.1 목적과 범위

새 destroy/repair를 먼저 늘리지 않고, Phase 6에 다음 optional search lane을 만들었다.
이 lane은 기본 활성화하지 않고 baseline과 별도 operator/search profile로 benchmark한다.

```text
complete feasible initial solution
  │
  ├─ [새 lane] exact local move search
  │     ├─ Change
  │     ├─ Swap
  │     └─ 별도 측정: RelationGroupExchange
  │
  └─ [기존] ALNS destroy → repair → SA
```

첫 benchmark는 lane 단독이어야 한다. 단독 효과를 확인하지 않은 상태에서 ALNS와 섞으면
개선의 원인을 알 수 없다. 단독 통과 후에만 “local-move warm start → ALNS intensification”
hybrid를 별도 후보로 검증한다.

### 6.2 필요한 구성요소

1. `OptaStyleMoveSelector` — 구현됨
   - mutable shift에 대한 Change와 mutable shift 두 개의 Swap만으로 시작한다.
   - 기본 비교군과 맞추기 위해 candidate value를 skill/availability proxy로 미리 제거하지 않고,
     exact score가 hard/soft 제약을 판정하게 한다.
   - pinned shift, same-value change, same-employee swap은 생성하지 않는다.
   - 기본 `UniformRandomUnionMoveIterator`는 Change/Swap **자식 selector를 균등하게** 고른다.
     따라서 현재의 Change/Swap 1:1 family 선택은 이 부분의 기본 동작과 일치한다. 다만 POJO는
     `SplittableRandom`, OptaPlanner는 working `Random`을 쓰고 각 자식 selector의 난수 소비와
     move undo 구현도 다르므로, seed별 move trace까지 같은 bit-for-bit clone은 아니다. 따라서
     artifact의 이름은 `OPTA_STYLE_CHANGE_SWAP_ONLY`이며, move topology와 acceptance를 분리
     검증하는 비교군이다.
   - `RelationGroupExchangeMove`는 OptaPlanner default에 없는 확장 move이므로 core Change/Swap
     benchmark와 분리해 세 번째 profile로 측정한다.

2. `OptaStyleLateAcceptancePolicy` — 구현됨
   - history length 400, 시작 score로 초기화한다.
   - `candidate >= current || candidate >= history[slot]`만 수락한다.
   - **수락된 step마다** history slot을 current score로 무조건 덮어쓴다.
   - 거절 후보는 history step을 진전시키지 않는다. 즉 한 step 안에서 후보를 scan하여
     첫 수락 후보만 commit한다.

3. `OptaStyleLocalSearchEngine` — 구현됨
   - `MoveTransaction`, `IncrementalScoreCalculator`, rollback verification을 재사용한다.
   - `InitialSolutionBuilder`가 infeasible complete 해를 반환한 경우, main Change/Swap 탐색 전에
     baseline ALNS와 동일한 10,000-evaluation relation-aware feasibility bootstrap을 수행한다.
     이 bootstrap은 OptaPlanner의 default move set을 흉내 내기 위한 연산자가 아니라 POJO
     initial-solution 경계의 안전 보정이며, 비용을 본 lane evaluation과 분리 기록한다.
   - candidate score, move type, accepted/rejected, history length, best 도달 evaluation을 artifact에
     남긴다.
   - 모든 candidate는 incremental score로 평가한다. full/incremental 검증은 모든 commit과
     global-best 후보, 1,000번째 rejected candidate, 최종 best에 수행하며, interval 자체를
     artifact에 기록한다. 안전 검증을 성능을 위해 임의로 제거하지 않는다.
   - CDI bean이나 `solver.engine` selector에는 등록하지 않았다.

4. benchmark profile — Change/Swap-only 구현됨
   - `POJO_OPTA_STYLE_CHANGE_SWAP` / operator set `OPTA_STYLE_CHANGE_SWAP_ONLY`
   - tuning dataset/seed와 holdout dataset/seed를 분리하고, 기존과 동일하게 crossed order,
     warm-up 제외, fixed-evaluation/wall-clock 분리, paired W/T/L 및 p10/median/p90을 유지한다.
   - 후속 profile: `OPTA_STYLE_CHANGE_SWAP_PLUS_RELATION_EXCHANGE`, 그 뒤에만
     `LOCAL_MOVE_THEN_ALNS`

### 6.3 성공/실패 판정

새 lane은 아직 Phase 0 승인 수치가 없으므로 default promotion 대상이 아니다. 다만 측정 결과는
다음의 진단 질문에 답해야 한다.

- preceptor holdout의 soft1/soft2 p10 열화가 baseline보다 줄어드는가?
- relation exchange가 final best를 실제로 만드는 run이 있는가?
- fixed evaluation에서 best 도달 evaluation 분포가 baseline보다 나빠지지 않는가?
- wall-clock p95와 rollback/score mismatch가 baseline 안전 수준을 유지하는가?
- Change/Swap만의 효과와 relation exchange 추가 효과를 분리할 수 있는가?

Phase 0의 비열화 기준과 tail guardrail은 별도 승인 전까지 임의의 숫자를 만들지 않는다.

## 7. named destroy/repair 후보의 올바른 순서

local-move lane을 먼저 측정한 뒤, 아래 후보를 한 개씩 추가한다. 각 후보는 작은 실패
fixture, unit/transaction/determinism test, 단독 benchmark, paired holdout gate를 거친다.

| 후보 | 해결할 실패 패턴 | local-move 이후에 추가하는 이유 |
|---|---|---|
| `EmployeeWindowRemoval` | 한 직원의 인접 일자 배정이 32h rest·day/holiday burden을 함께 악화 | Change/Swap으로 못 푸는 3~5 shift 상관관계에만 large destroy 사용 |
| `NightChainRemoval` | 연속 night와 다음 day/recovery가 묶인 chain | proxy가 아닌 실제 night/recovery contribution을 사용해야 함 |
| `WorstContributionRemoval` | 하나 또는 작은 집합이 exact score의 큰 손실을 만듦 | `FullScoreCalculator` breakdown 기반이어야 하며 proxy 비용만으로 선택하면 안 됨 |
| `FairnessHotspotRemoval` | burden 제곱 penalty가 큰 직원/shift 군 | fairness evaluator의 실제 marginal contribution을 사용 |
| `Regret3Repair` | 여러 shift의 대안 폭이 좁아 greedy/regret2가 잘못된 순서를 선택 | proxy regret3가 아니라 exact/validated marginal ranking 필요 |
| `FairnessAwareRepair` | fairness를 개선하려다 higher soft level을 악화 | 사전식 score를 보존한 bounded exact candidate 비교 필요 |
| `RandomizedTopKRepair` | 완전 동률 또는 근접한 후보가 반복되어 다양성이 사라짐 | exact candidate top-K 안에서만 seeded random tie-break를 사용 |

`PreceptorRelationGroupRemoval + RelationAwareRepair`는 relation group을 이동시키는 후보가
아니다. relation group 개선은 위 목록보다 먼저 `RelationGroupExchangeMove` lane에서
독립 측정한다.

## 8. 다음 실행 단위

1. small fixture를 확장한다.
   - single Change가 fairness를 개선하는 fixture
   - Swap만이 feasible fairness/preference 개선을 만드는 fixture
   - relation group이 original restore가 아니라 다른 slot과 원자 교환되어야만 개선되는 fixture
2. relation-group exchange를 별도 move set으로 추가하되, Change/Swap-only와 독립 paired
   benchmark를 실행한다.
3. 같은 1:1 family 비율을 유지한 채, OptaPlanner와 POJO의 난수 소비 차이가 결과에 미치는
   영향을 tuning partition에서 측정한다. 이 비교는 seed trace 동일성을 요구하지 않고, 같은
   seed 집합의 paired score 분포만 비교한다.
4. lane 단독 tuning benchmark 후 holdout fixed-evaluation과 wall-clock을 실행한다.
5. 승인 수치가 확정된 뒤에만 baseline default에 promotion한다.

현재 구현의 smoke 명령은 다음과 같다.

```bash
scripts/benchmark/run-phase6-opta-style-move-benchmark.sh quick
```

smoke artifact는
`benchmark-artifacts/phase6/smoke/quick/20260722T155246Z/`에 있다. 이는 request 입력 두 seed의
실행 형식 검증일 뿐 holdout promotion 근거가 아니다.

### 8.1 폐기한 측정과 재실행 조건

`benchmark-artifacts/phase6/holdout/wall-clock/20260722T160123Z/`는 최초 Change/Swap lane이
preceptor 초기해의 infeasibility를 bootstrap하지 않은 상태에서 생성한 artifact다. 그 결과
`preceptor.json` 20 run 모두 hard score 0을 잃었으므로, 이 artifact는 lane의 품질·tail·fixed
budget을 정하는 근거로 사용할 수 없다.

이후 runner는 feasibility bootstrap을 추가했고, `OptaStyleLocalSearchEngineTest`가 해당
`preceptor.json` 경로에서 hard score 0과 bootstrap evaluation 기록을 회귀 검증한다. 따라서
수정된 runner로 wall-clock holdout을 다시 실행한 결과만 승인·fixed-evaluation 입력으로 사용한다.

### 8.2 수정 후 wall-clock holdout 결과

수정 후 artifact는
`benchmark-artifacts/phase6/holdout/wall-clock/20260723T115150Z/`이다. 80개 모든 run이
hard score 0이고 execution failure, rollback failure, score mismatch는 모두 0이다.

| dataset | pairs | POJO 대 Opta W/T/L | 관찰 |
|---|---:|---:|---|
| ALL | 80 | 24 / 45 / 11 | non-loss 86.25%, paired soft2 p10 -1,254 |
| fairness | 20 | 4 / 5 / 11 | soft2 p10 -2, local move 단독으로는 열세 |
| preceptor | 20 | 20 / 0 / 0 | soft1 p10 +1,440, soft2 p10 -1,394 |
| request | 20 | 0 / 20 / 0 | 완전 동률 |
| sample | 20 | 0 / 20 / 0 | 완전 동률 |

preceptor run마다 feasibility bootstrap은 10,000 evaluation을 사용했고, 이 값은 main
Change/Swap candidate evaluation과 분리되어 raw artifact에 남아 있다. 전체 Change/Swap
기여는 Reassign 753회 global-best event / 68회 final-best run, Swap 192회 / 12회다.

사전식 comparator가 실제로 승패를 결정한 최초 레벨로 80 pair를 분해하면 다음과 같다.
`eligible`은 그보다 상위 모든 level이 동률이라 이 level까지 비교가 진행된 pair 수다.

| 최초 비교 level | eligible | POJO W/T/L at level | 다음 level로 동률 전달 |
|---|---:|---:|---:|
| hard[0] | 80 | 0 / 80 / 0 | 80 |
| soft[0] — 32h 휴식 | 80 | 8 / 72 / 0 | 72 |
| soft[1] — 기피일 | 72 | 12 / 60 / 0 | 60 |
| soft[2] — fairness | 60 | 4 / 45 / 11 | 45 |
| soft[3] — 희망일 | 45 | 0 / 45 / 0 | 45 (완전 동률) |

preceptor의 20승은 soft[0]에서 8건, soft[1]에서 12건이 먼저 갈렸고, fairness의 4승·11패는
soft[2]에서 갈렸다. request/sample의 40 pair는 모든 level이 완전 동률이다.

이 결과는 해당 lane이 preceptor topology를 보완할 가능성을 보이지만, fairness tail을
악화시키므로 default promotion 근거는 아니다. 또한 ALNS baseline과 Change/Swap lane을 같은
시점에 직접 paired 비교한 artifact는 아직 없으므로, 두 profile을 섞는 hybrid 결정도 내리지
않는다.

fixed-evaluation 재현성 측정에는 wall-clock `ALL` 행의 nearest-rank p10을 사용한다.

```text
OPTAPLANNER_EVALUATION_LIMIT = 2296836
POJO_EVALUATION_LIMIT        = 381210
```

여기서 POJO 값은 bootstrap을 제외한 exact-move candidate evaluation이고, OptaPlanner 값은
score calculation이다. 두 단위를 throughput으로 같다고 해석하지 않는다. 목적은 각 엔진에
wall-clock lower-tail에서 관측된 예산을 각각 부여해 seed별 품질과 결정론을 다시 확인하는 것이다.

### 8.3 fixed-evaluation holdout 결과

fixed artifact는
`benchmark-artifacts/phase6/holdout/fixed/20260723T145818Z/`이다. 모든 engine/dataset/seed
조합은 두 repeat에서 assignment fingerprint, score, evaluation count, termination reason이
동일했다(80개 repeat group, nondeterministic group 0). 모든 160 run은 hard score 0이고,
rollback failure, score mismatch, execution failure도 0이다.

| dataset | pairs | POJO 대 Opta W/T/L | 관찰 |
|---|---:|---:|---|
| ALL | 80 | 24 / 44 / 12 | paired soft2 p10 -1,216 |
| fairness | 20 | 4 / 4 / 12 | 12패 모두 hard/soft0/soft1/soft3 동률, soft2 -2 |
| preceptor | 20 | 20 / 0 / 0 | soft1 p10 +960, soft2 p10 -1,334 |
| request | 20 | 0 / 20 / 0 | 완전 동률 |
| sample | 20 | 0 / 20 / 0 | 완전 동률 |

fixed comparator의 최초 결정 level 분해는 hard[0] 0/80/0, soft[0] 8/72/0,
soft[1] 12/60/0, soft[2] 4/44/12, soft[3] 0/44/0 W/T/L이다. 따라서 preceptor의 상위
soft[0]/soft[1] 이득과 fairness soft[2] 열화는 wall-clock 종료 시점의 우연이 아니라 고정
평가 예산에서도 재현된다.

안전성·결정론 측정은 통과했지만, Phase 0의 수치 gate가 아직 승인되지 않았고 fairness
soft[2] tail 열화가 재현되므로 이 lane은 **test-only / 기본 비활성**으로 유지한다. 다음
연산자나 hybrid의 승격 근거로 사용하지 않는다.

## 9. 근거 위치

- Solver adapter: `src/main/java/org/acme/solver/optaplanner/OptaPlannerSolverEngine.java`
- Domain: `src/main/java/org/acme/model/EmployeeSchedule.java`,
  `src/main/java/org/acme/model/Shift.java`
- ALNS main loop/transaction: `src/main/java/org/acme/solver/alns/AlnsSolverEngine.java`,
  `src/main/java/org/acme/solver/alns/AlnsIteration.java`
- ALNS relation repair: `src/main/java/org/acme/solver/alns/PreceptorRelationGroupRemoval.java`,
  `src/main/java/org/acme/solver/alns/RelationAwareRepair.java`
- Existing move model: `src/main/java/org/acme/solver/lahc/SeededMoveSelector.java`,
  `src/main/java/org/acme/solver/move/RelationGroupExchangeMove.java`
- Existing LAHC policy: `src/main/java/org/acme/solver/lahc/LahcAcceptancePolicy.java`
- Holdout artifacts:
  `benchmark-artifacts/phase6/holdout/fixed/20260719T143015Z/report.md`,
  `benchmark-artifacts/phase6/holdout/wall-clock/20260717T134958Z/report.md`
- OptaPlanner implementation evidence: local Maven dependency
  `optaplanner-core-impl-10.0.0.jar`의 `DefaultSolverFactory`,
  `DefaultConstructionHeuristicPhaseFactory`, `DefaultLocalSearchPhaseFactory`,
  `AcceptorFactory`, `LateAcceptanceAcceptor` bytecode.
