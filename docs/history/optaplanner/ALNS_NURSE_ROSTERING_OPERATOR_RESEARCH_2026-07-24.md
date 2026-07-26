# 간호사 로스터링 ALNS destroy/repair 조사와 적용 판단

작성일: 2026-07-24  
대상: `every-shift-quarkus` POJO 탐색/ALNS  
상태: 조사 완료, 형평성 hotspot 후보는 test-only로 구현했으나 승격 보류

## 1. 결론

현재 fixed holdout의 유일한 사전식 패배는 `fairness.json` 12건이며, 모두
`[hard, soft0, soft1, soft2, soft3]` 중 soft2만 `-2`이다. 대표 seed 101의 같은
최종해 교차 계산에서는 POJO `FullScoreCalculator`와 OptaPlanner
`SolutionManager`가 각각 정확히 일치했다. 따라서 점수 계산 문제가 아니라 탐색
이웃과 후보 도달 순서 문제이다.

대표 seed 101의 soft2 breakdown은 다음과 같다.

| 엔진 | 주간/저녁 | 휴일 | 야간 | 합계 |
|---|---:|---:|---:|---:|
| OptaPlanner | 5,866 | 1,301 | 459 | 7,626 |
| POJO Opta-style Change/Swap | 5,868 | 1,301 | 459 | 7,628 |

이 결과와 문헌을 함께 보면 첫 후보는 야간 체인이나 일반 related removal이 아니라
주간/저녁 제곱 부담의 한계 기여가 큰 배정을 제거하고, 복구 시 정확한 soft2 삽입
증분을 쓰는 조합이어야 한다. 이에 따라 다음 한 쌍만 구현했다.

- `FAIRNESS_HOTSPOT_REMOVAL`
- `FAIRNESS_AWARE_REGRET_2_REPAIR`

production `AlnsSolverEngine()`의 기본 연산자와 설정은 바꾸지 않았다. 후보는
`POJO_ALNS_FAIRNESS_HOTSPOT` benchmark lane에서만 baseline 세트에 추가된다.
짧은 연구 smoke에서 선택과 현재해 이동은 확인했지만 global-best 기여는 0이므로
현재 판단은 **승격 보류**이다.

## 2. 1차 문헌과 공식 문제 자료

### 2.1 Kiefer, *Large Neighborhood Search for the Nurse Rostering Problem* (2015)

- 원문: [TU Wien 석사학위 논문 PDF](https://repositum.tuwien.at/bitstream/20.500.12708/4996/2/Kiefer%20Alexander%20-%202015%20-%20Large%20Neighborhood%20Search%20for%20the%20Nurse%20Rostering...pdf)
- 저장소 레코드: [TU Wien Repositum](https://repositum.tuwien.at/handle/20.500.12708/4996)

이 연구는 nurse rostering에 직접 적용한 LNS라는 점에서 현재 판단의 가장 강한
근거다.

- penalty removal은 개별 assignment보다 soft penalty가 큰 **직원 roster**를
  선택해 그 직원의 배정을 제거한다.
- 민감도 분석에서 penalty removal 제외 시 결과 열화가 가장 컸다. 논문은 직원
  단위 roster를 풀면 연속근무·succession처럼 서로 연결된 제약을 함께 재구성할 수
  있다는 점을 이유로 든다.
- related removal은 같은 직원, 같은 날, 금지된 succession, skill 관련성을
  이용하고 순위 편향 무작위 선택으로 다양성을 준다.
- greedy repair의 opportunity cost와 staffing 우선 repair를 사용하고, insertion
  cost에 무작위 noise를 더한 구성이 유효했다.
- 반면 이 연구의 문제에서는 regret repair를 시험한 뒤 최종 구성에서 제외했다.
  따라서 nurse rostering이라는 이유만으로 regret-k를 자동 채택할 근거는 없다.

현재 적용:

- 직원 penalty removal의 핵심을 현재 q 상한 12 안에서 사용할 수 있도록
  employee 전체 roster 대신 정확한 soft2 **한계 감소량**이 큰 mutable assignment를
  고르는 bounded hotspot으로 축소했다.
- noise는 별도 자유도를 추가하므로 이번 한 쌍의 기여를 먼저 측정하기 위해
  적용하지 않았다.

### 2.2 He & Qu, constraint-directed LNS (2009)

- 원문: [arXiv PDF](https://arxiv.org/pdf/0910.1253)

간호사-날짜 행렬에서 작은 generic swap만으로는 여러 변수를 동시에 바꿔야 하는
제약 구조를 넘기 어렵다고 설명한다.

- 모든 직원의 고정 길이 날짜 window를 푸는 방법
- boundary 위반을 줄이기 위한 overlapping window
- soft cost가 큰 직원 행과 전파로 연결된 변수를 선택하는 cost-directed LNS

현재 적용 판단:

- employee-window는 soft0의 휴식/연속성 또는 경계 위반이 실제 패배 원인으로
  확인될 때 유력하다.
- 현재 대표 실패는 휴일·야간이 동률이고 주간/저녁 형평성만 2점 차이므로 이번
  후보에는 날짜 window를 섞지 않는다.

### 2.3 Ceschia & Schaerf, multi-day neighborhoods (PATAT 2018)

- 원문: [PATAT 2018 논문 PDF](https://www.patatconference.org/patat2018/files/proceedings/paper46.pdf)

하루짜리 change/swap보다 `k`일 연속 구간을 동시에 바꾸는 MultiChange와
MultiSwap이 nurse rostering에서 더 나은 결과를 보였고, MultiChange는 연속 배정을
유도하도록 후속 날짜의 선택을 안내한다.

현재 적용 판단:

- employee-window와 night-chain 후보의 직접 근거다.
- 그러나 현재 `fairness.json` 대표 패배에는 soft0 야간/회복 차이가 없다.
  실패 좌표가 확인되지 않은 상태에서 multi-day operator를 동시에 추가하지 않는다.

### 2.4 Lü & Hao, adaptive neighborhood search (2012)

- 원문: [저자 공개 PDF](https://leria-info.univ-angers.fr/~jinkao.hao/papers/EJORLuHao2011.pdf)

간호사-날짜 행렬에서 같은 날의 근무를 다른 간호사에게 옮기는 One-Shift와
Two-Swap을 결합하고, 집중·중간·다양화 단계를 적응적으로 전환한다.

현재 적용 판단:

- 현재 `REASSIGN`/`SWAP`과 목적이 중복된다.
- fixed holdout에서 Change/Swap이 이미 80/80 feasible이고 24/44/12이므로 동일한
  단일 이동을 ALNS 이름으로 중복 추가하지 않는다.

### 2.5 Ropke & Pisinger, ALNS (2006)

- 원문: [DTU technical report PDF](https://backend.orbit.dtu.dk/ws/portalfiles/portal/3154899/An%2Badaptive%2Blarge%2Bneighborhood%2Bsearch%2Bheuristic%2Bfor%2Bthe%2Bpickup%2Band%2Bdelivery%2Bproblem%2Bwith%2Btime%2Bwindows_TechRep_ropke_pisinger.pdf)

간호사 로스터링 연구는 아니지만 ALNS 연산자 정의의 1차 근거다.

- worst removal은 `f(s) - f(s_without_i)` 형태의 한계 기여가 큰 항목을 제거한다.
- regret-2는 두 번째 최선과 최선 insertion의 차이를 사용하며, regret-k는 여러
  차선 후보를 잃는 비용을 합산한다.
- 순위 편향 무작위 제거와 insertion noise는 같은 후보만 반복하는 것을 막는다.
- repair가 neighborhood 안의 해를 휴리스틱하게 고르므로 noise는 SA와 역할이
  완전히 중복되지 않는다.

현재 적용:

- `FAIRNESS_HOTSPOT_REMOVAL`은 worst-removal 원리를 전체 scalar score가 아니라
  관측된 실패 좌표 soft2에 제한한다.
- 현재 `REGRET_2_REPAIR`가 이미 있으므로 regret-3/4는 이번 후보에 추가하지 않는다.
- randomized-top-k/noise는 hotspot pair 자체의 unique-best 기여가 확인된 다음
  독립 후보로 비교해야 한다.

### 2.6 Pisinger & Ropke, LNS/ALNS handbook

- 원문: [DTU handbook chapter PDF](https://backend.orbit.dtu.dk/ws/files/5293785/Pisinger.pdf)

큰 neighborhood가 항상 우월하지 않고 작은 neighborhood가 더 빠른 반복으로
비슷하거나 더 좋은 결과를 낼 수 있음을 정리한다. 따라서 연산자 수와 q를 한꺼번에
늘리는 방식은 근거가 약하다.

### 2.7 INRC-II 공식 문제 설명

- 원문: [INRC-II 문제 논문](https://arxiv.org/abs/1501.04177)

다단계 nurse rostering에서는 이전 기간의 이력과 다음 기간 경계가 연속근무,
주말, shift succession의 의미를 바꾼다. employee-window나 night-chain을 도입할
때 historic/published/pinned 경계를 무시하면 안 된다는 근거로 사용했다.

## 3. 현재 코드와 중복 감사

### 3.1 기존 destroy

| 기존 연산자 | 현재 동작 | 문헌과의 관계 | 판단 |
|---|---|---|---|
| `RANDOM_REMOVAL` | mutable shift 무작위 제거 | 표준 random removal | 유지 |
| `RELATED_SHIFT_REMOVAL` | seed와 같은 직원, 가까운 실제일, 같은 shift code 우선 | related removal 일부 | 이미 존재; skill/succession 전체 모델은 아님 |
| `PRECEPTOR_RELATION_GROUP_REMOVAL` | 같은 실제일·교대의 preceptor 관계 묶음 | constraint-directed group | preceptor 입력에서 상위 목적식 승리 중이므로 유지 |

### 3.2 기존 repair

| 기존 연산자 | 현재 동작 | 중복/한계 | 판단 |
|---|---|---|---|
| `GREEDY_REPAIR` | 지역 insertion cost 최소 | soft2 정확한 제곱 증분 없음 | 유지 |
| `REGRET_2_REPAIR` | 두 번째 후보와 최선 후보 차이 | regret-2 이미 존재 | regret-k 즉시 추가 안 함 |
| `RELATION_AWARE_REPAIR` | 관계 직원을 원배정 중심으로 복구 | 원배정 복원이 잦아 탐색 이동이 약할 수 있음 | 이번 범위에서는 변경 안 함 |

`RepairContext#insertionCost`는 complete `RosterScore`가 아니다. 같은 날, overlap,
최소 휴식, 배정 수, 선호/비선호, preceptor 관계만 근사한다. soft0의 모든 야간
회복/32시간 조건과 soft2의 정확한 제곱 부담은 포함하지 않는다. 새 repair도 이
기존 비용을 버리지 않고 정확한 soft2 삽입 증분만 더한다. 따라서 점수 의미는
바뀌지 않지만 complete 사전식 점수와 동치라고 주장할 수는 없다.

## 4. 실패 패턴 정의

고정 artifact:
`benchmark-artifacts/phase6/holdout/fixed/20260723T145818Z`

- 80/80 hard `0`
- 전체 W/T/L `24/44/12`
- 12패는 모두 `fairness.json`
- 12패 모두 hard, soft0, soft1, soft3 동률 후 soft2만 `-2`
- 대표 seed 101에서 두 점수기 모두:
  - OptaPlanner 해: `[0]hard/[0/0/-7626/0]soft`
  - POJO 해: `[0]hard/[0/0/-7628/0]soft`
- 대표 seed 101의 차이는 주간/저녁 형평성 `5866` 대 `5868`; 휴일과 야간은 동일
- 두 최종해 사이 assignment가 다른 shift는 275개이므로 단일 잘못 배정 하나가
  아니라 서로 다른 local basin에서 같은 상위 점수와 거의 같은 형평성에 도달한
  패턴이다.

따라서 이번 실패 패턴은 다음과 같이 정의한다.

> 상위 목적식이 같은 feasible 해들 사이에서 주간/저녁 shift-type 제곱 부담을
> 2 더 낮추는 basin으로 이동해야 하지만, 예산 안의 일반 Change/Swap과 현재
> ALNS insertion proxy가 그 basin을 최종 best로 만들지 못한다.

## 5. 구현 후보

### 5.1 `FAIRNESS_HOTSPOT_REMOVAL`

- 현재 complete assignment에서 직원별로 다음 soft2 부담을 계산한다.
  - 야간 부담 합의 제곱
  - 휴일 부담 합의 제곱
  - 야간을 제외한 shift type별 근무 수 제곱
  - 저녁 shift type은 가중치 5
- mutable assignment 하나를 제거할 때 감소하는 정확한 한계 부담을 계산한다.
- 한계 감소 내림차순, 직원 전체 부담 내림차순, shift index 오름차순으로 안정
  정렬하고 현재 q만큼 선택한다.
- entire employee roster removal의 취지를 유지하되 기존 absolute removal limit
  12와 immutable 경계를 지킨다.

### 5.2 `FAIRNESS_AWARE_REGRET_2_REPAIR`

- 기존 `insertionCost`에 해당 직원을 선택할 때 늘어나는 정확한 soft2 제곱 부담을
  더한다.
- 기존 hard/관계/선호 proxy의 큰 penalty를 유지한다.
- 후보 순서는 같은 seed에서 결정론적이며, 최선 비용 동률에만 seed 기반 tie-break를
  사용한다.
- 기존 regret-2와 독립 ID를 사용해 선택 분포와 best 기여를 분리 집계한다.

### 5.3 적용하지 않은 후보

| 후보 | 근거 | 이번 보류 이유 |
|---|---|---|
| employee-window | He & Qu, Ceschia & Schaerf | 대표 실패가 window 경계/soft0가 아님 |
| related-shift/night-chain | Kiefer, multi-day 연구 | 기존 related removal이 일부 존재하고 야간 좌표는 동률 |
| 전체 scalar worst-contribution | 표준 ALNS | 사전식 score scalar화는 현재 점수 위계를 왜곡할 수 있음 |
| regret-3/4 | Ropke & Pisinger | nurse 연구 Kiefer에서는 regret 제외; regret-2 이미 존재 |
| randomized-top-k/noise | Kiefer, Ropke & Pisinger | hotspot pair 기여 전 자유도 중첩을 피함 |
| staffing-first insertion | Kiefer | 현재 입력은 한 shift 한 배정 모델이고 understaffing 구조가 다름 |

## 6. 검증 결과와 판단

작은 fixture:

- operator 형평성 합계와 `FullScoreCalculator` soft2 일치
- 저녁 3회 직원의 제거 한계 부담 `25`
- repair가 기존 배정 수가 같은 후보 중 저녁 제곱 증분이 작은 직원을 선택
- 후보 soft2 `+20`
- reject 시 assignment/fingerprint/score cache 완전 rollback
- 같은 seed의 assignment와 score 결정론 일치

연구 smoke artifact:
`benchmark-artifacts/phase6/research-smoke/fairness-hotspot-20260724-v2`

| profile | pairs | feasible Opta/후보 | W/T/L | Opta score | 후보 score | best eval 후보 | p95 ms Opta/후보 | rollback fail |
|---|---:|---:|---:|---|---|---:|---:|---:|
| wall-clock 5초 | 1 | 1/1 | 0/0/1 | `[0]hard/[0/0/-7626/0]soft` | `[0]hard/[0/0/-7632/0]soft` | 0 | 5004/4992 | 0 |
| fixed 20,000/200 | 1 | 1/1 | 0/0/1 | `[0]hard/[0/0/-7630/0]soft` | `[0]hard/[0/0/-7632/0]soft` | 0 | 423/2590 | 0 |

단일 pair이므로 p10/median/p90은 각 표의 score와 모두 같다. 이 결과는 gate
판정용 holdout이 아니라 실행 가능성과 초기 방향을 확인한 smoke다.

두 profile 합산 후보 pair 기여:

| operator | 선택 | global best | current 개선 | 악화 수락 | reject | final best 기여 run |
|---|---:|---:|---:|---:|---:|---:|
| `FAIRNESS_HOTSPOT_REMOVAL` | 377 | 0 | 32 | 174 | 171 | 0 |
| `FAIRNESS_AWARE_REGRET_2_REPAIR` | 377 | 0 | 32 | 174 | 171 | 0 |

연산자가 후보를 만들고 현재해를 이동시키는 것은 확인됐지만 unique/final best
기여가 없다. 따라서 현 상태에서는 다음을 선언할 수 없다.

- production 기본 활성화
- 기존 operator 대체
- fixed holdout 개선
- Phase 7 shadow 전환 준비 완료

## 7. 후속 측정 명령

긴 80-pair는 이 작업 환경에서 완료하지 않았다. 먼저 wall-clock 결과에서 각
엔진의 평가 수 p10을 확정한 뒤 fixed profile에 그대로 넣어야 한다.

```bash
cd /Users/brown/workspace/every-shift-quarkus
OUTPUT_DIR=/Users/brown/workspace/every-shift-quarkus/benchmark-artifacts/phase6/holdout/wall-clock/fairness-hotspot-$(date -u +%Y%m%dT%H%M%SZ) \
  /Users/brown/workspace/every-shift-quarkus/scripts/benchmark/run-phase6-fairness-hotspot-benchmark.sh wall-clock
```

예상 artifact:

- `raw.jsonl`
- `summary.json`
- `report.md`

wall-clock report의 OptaPlanner/POJO 평가 수 p10을 각각 아래 환경 변수에 넣는다.

```bash
cd /Users/brown/workspace/every-shift-quarkus
OPTAPLANNER_EVALUATION_LIMIT=<wall-clock Opta p10> \
POJO_EVALUATION_LIMIT=<wall-clock POJO p10> \
OUTPUT_DIR=/Users/brown/workspace/every-shift-quarkus/benchmark-artifacts/phase6/holdout/fixed/fairness-hotspot-$(date -u +%Y%m%dT%H%M%SZ) \
  /Users/brown/workspace/every-shift-quarkus/scripts/benchmark/run-phase6-fairness-hotspot-benchmark.sh fixed
```

기본 스크립트는 warm-up을 실행하되 집계에서 제외하고, 같은 입력/seed pair에 대해
엔진 실행 순서를 교차한다. wall-clock과 fixed-evaluation 결과를 합쳐 해석하지
않는다.

## 8. 아직 필요한 사용자 결정

`docs/benchmarks/PHASE6_GATE_MEASUREMENT.md`의 수치 gate는 아직 합의되지 않았다.
다음 값을 사용자와 합의하기 전에는 promotion이나 Phase 7 진입을 선언할 수 없다.

- paired loss 허용 개수 또는 최소 non-loss 비율
- 상위 soft level별 p10 허용 열화량
- best 도달 평가 횟수 개선 요구량
- wall-clock p95 상대/절대 허용 한계
- rollback failure/score mismatch 허용 건수
- hard `0` 요구의 적용 범위
