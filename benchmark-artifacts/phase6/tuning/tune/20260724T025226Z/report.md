# Phase 6 SA/ALNS 하이퍼파라미터 레이싱

- 생성 시각: `2026-07-24T03:31:43.786606Z`
- production 기본값 변경: 없음
- promotion gate 적용: 없음
- 훈련 seed: `[201, 202]`
- 검증 seed: `[301, 302]`
- 잠긴 기존 holdout 101..110: 설정 선택에 사용하지 않음
- fixed-evaluation: OptaPlanner score calculation과 ALNS complete candidate evaluation은 서로 다른 단위이므로 throughput 비교로 해석하지 않음
- 설정 선택: case별 사전식 feasible → pairwise 승 → 패 → ordinal rank sum; 수치 promotion gate가 아님

## 단계별 리더

| 단계 | partition | 후보 | case | eval | 1위 | feasible | pair W/T/L | rank sum | global best |
|---|---|---:|---:|---:|---|---:|---:|---:|---:|
| 01-sa | training | 16 | 8 | 1000 | `p0_2-r0_005-c64-d0_05-q8-a2-x0_2-s100-foff` | 8 | 15/105/0 | 8 | 1 |
| 02-calibration | training | 6 | 8 | 1000 | `p0_2-r0_005-c64-d0_05-q8-a2-x0_2-s100-foff` | 8 | 5/35/0 | 8 | 1 |
| 03-destroy | training | 24 | 8 | 1000 | `p0_2-r0_01-c64-d0_02-q8-a2-x0_2-s100-foff` | 8 | 40/125/19 | 27 | 2 |
| 04-repair | training | 7 | 8 | 1000 | `p0_2-r0_01-c64-d0_02-q8-a2-x0_2-s100-foff` | 8 | 2/46/0 | 8 | 2 |
| 05-adaptive | training | 25 | 8 | 1000 | `p0_2-r0_005-c64-d0_02-q8-a2-x0_05-s250-foff` | 8 | 46/144/2 | 10 | 4 |
| 06-operator-set | training | 7 | 8 | 1000 | `p0_2-r0_005-c64-d0_02-q8-a2-x0_05-s250-foff` | 8 | 15/30/3 | 11 | 4 |
| 07-validation | validation | 6 | 8 | 5000 | `p0_2-r0_01-c64-d0_02-q8-a2-x0_2-s250-fon` | 8 | 11/25/4 | 12 | 6 |

## 검증 후 비교 후보

1. `p0_2-r0_01-c64-d0_02-q8-a2-x0_2-s250-fon`: `SearchConfig[initialAcceptanceProbability=0.2, finalTemperatureRatio=0.01, calibrationAttempts=64, destroyRate=0.02, qMax=8, maxRepairAttempts=2, reactionFactor=0.2, segmentLength=250, fairnessOperator=true]`
2. `p0_2-r0_005-c64-d0_02-q8-a2-x0_2-s250-fon`: `SearchConfig[initialAcceptanceProbability=0.2, finalTemperatureRatio=0.005, calibrationAttempts=64, destroyRate=0.02, qMax=8, maxRepairAttempts=2, reactionFactor=0.2, segmentLength=250, fairnessOperator=true]`

## OptaPlanner paired 비교

| profile | config | pairs | feasible Opta/ALNS | W/T/L | Opta score p10/median/p90 | ALNS score p10/median/p90 | paired delta p10/median/p90 | p95 ms Opta/ALNS | best eval p50/p90 Opta | best eval p50/p90 ALNS | rollback attempt/fail |
|---|---|---:|---:|---:|---|---|---|---:|---|---|---:|
| fixed-evaluations | `p0_2-r0_01-c64-d0_02-q8-a2-x0_2-s250-fon` | 8 | 8/8 | 1/2/5 | [0]hard/[-2880/-7680/-5763/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-5279/0]soft | [0]hard/[0/-5280/-7013/0]soft / [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-5279/0]soft | [0, 0, -2400, -1288, 0] / [0, 0, 0, -4, 0] / [0, 2880, 2400, 0, 0] | 60268/8174 | 58875/2110428 | 0/4868 | 15524/0 |
| fixed-evaluations | `p0_2-r0_005-c64-d0_02-q8-a2-x0_2-s250-fon` | 8 | 8/8 | 1/1/6 | [0]hard/[-2880/-7680/-5763/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-5279/0]soft | [0]hard/[0/-5280/-7013/0]soft / [0]hard/[0/0/-7630/0]soft / [0]hard/[0/0/-5279/0]soft | [0, 0, -2400, -1288, 0] / [0, 0, 0, -4, 0] / [0, 2880, 2400, 0, 0] | 60268/8276 | 58875/2110428 | 0/4236 | 14780/0 |
| wall-clock | `p0_2-r0_01-c64-d0_02-q8-a2-x0_2-s250-fon` | 8 | 8/8 | 1/1/6 | [0]hard/[-3360/-9600/-5879/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-5279/0]soft | [0]hard/[0/-5280/-6873/0]soft / [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-5279/0]soft | [0, 0, 0, -1282, 0] / [0, 0, 0, -4, 0] / [0, 3360, 4320, 0, 0] | 10002/9991 | 58875/355352 | 0/8062 | 21561/0 |
| wall-clock | `p0_2-r0_005-c64-d0_02-q8-a2-x0_2-s250-fon` | 8 | 8/8 | 1/2/5 | [0]hard/[-3360/-9600/-5879/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-5279/0]soft | [0]hard/[0/-5280/-6873/0]soft / [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-5279/0]soft | [0, 0, 0, -1282, 0] / [0, 0, 0, -4, 0] / [0, 3360, 4320, 0, 0] | 10002/9991 | 58875/355352 | 0/8062 | 20201/0 |

## 연산자 선택·기여

### fixed-evaluations / `p0_2-r0_01-c64-d0_02-q8-a2-x0_2-s250-fon`

| 종류 | operator | 선택 | global best | current 개선 | 악화 수락 | 거절 | final best |
|---|---|---:|---:|---:|---:|---:|---:|
| destroy | FAIRNESS_HOTSPOT_REMOVAL | 12545 | 3 | 177 | 9131 | 3234 | 2 |
| destroy | PRECEPTOR_RELATION_GROUP_REMOVAL | 9632 | 0 | 79 | 5978 | 3575 | 0 |
| destroy | RANDOM_REMOVAL | 5454 | 3 | 78 | 574 | 4799 | 1 |
| destroy | RELATED_SHIFT_REMOVAL | 12369 | 0 | 68 | 8385 | 3916 | 0 |
| repair | FAIRNESS_AWARE_REGRET_2_REPAIR | 12545 | 3 | 177 | 9131 | 3234 | 2 |
| repair | GREEDY_REPAIR | 5960 | 1 | 44 | 3189 | 2726 | 0 |
| repair | REGRET_2_REPAIR | 6035 | 1 | 64 | 2983 | 2987 | 1 |
| repair | RELATION_AWARE_REPAIR | 15460 | 1 | 117 | 8765 | 6577 | 0 |

### fixed-evaluations / `p0_2-r0_005-c64-d0_02-q8-a2-x0_2-s250-fon`

| 종류 | operator | 선택 | global best | current 개선 | 악화 수락 | 거절 | final best |
|---|---|---:|---:|---:|---:|---:|---:|
| destroy | FAIRNESS_HOTSPOT_REMOVAL | 12807 | 1 | 163 | 9929 | 2714 | 0 |
| destroy | PRECEPTOR_RELATION_GROUP_REMOVAL | 9523 | 0 | 63 | 5985 | 3475 | 0 |
| destroy | RANDOM_REMOVAL | 5311 | 2 | 73 | 535 | 4701 | 1 |
| destroy | RELATED_SHIFT_REMOVAL | 12359 | 2 | 68 | 8399 | 3890 | 2 |
| repair | FAIRNESS_AWARE_REGRET_2_REPAIR | 12807 | 1 | 163 | 9929 | 2714 | 0 |
| repair | GREEDY_REPAIR | 6067 | 1 | 44 | 3302 | 2720 | 0 |
| repair | REGRET_2_REPAIR | 5768 | 2 | 47 | 2864 | 2855 | 2 |
| repair | RELATION_AWARE_REPAIR | 15358 | 1 | 113 | 8753 | 6491 | 1 |

### wall-clock / `p0_2-r0_01-c64-d0_02-q8-a2-x0_2-s250-fon`

| 종류 | operator | 선택 | global best | current 개선 | 악화 수락 | 거절 | final best |
|---|---|---:|---:|---:|---:|---:|---:|
| destroy | FAIRNESS_HOTSPOT_REMOVAL | 16614 | 1 | 327 | 11468 | 4818 | 0 |
| destroy | PRECEPTOR_RELATION_GROUP_REMOVAL | 16103 | 0 | 124 | 11569 | 4410 | 0 |
| destroy | RANDOM_REMOVAL | 7356 | 4 | 165 | 758 | 6429 | 3 |
| destroy | RELATED_SHIFT_REMOVAL | 17478 | 1 | 169 | 11404 | 5904 | 1 |
| repair | FAIRNESS_AWARE_REGRET_2_REPAIR | 16614 | 1 | 327 | 11468 | 4818 | 0 |
| repair | GREEDY_REPAIR | 8374 | 2 | 101 | 4507 | 3764 | 1 |
| repair | REGRET_2_REPAIR | 8010 | 1 | 146 | 3913 | 3950 | 1 |
| repair | RELATION_AWARE_REPAIR | 24553 | 2 | 211 | 15311 | 9029 | 2 |

### wall-clock / `p0_2-r0_005-c64-d0_02-q8-a2-x0_2-s250-fon`

| 종류 | operator | 선택 | global best | current 개선 | 악화 수락 | 거절 | final best |
|---|---|---:|---:|---:|---:|---:|---:|
| destroy | FAIRNESS_HOTSPOT_REMOVAL | 17309 | 0 | 318 | 12865 | 4126 | 0 |
| destroy | PRECEPTOR_RELATION_GROUP_REMOVAL | 15809 | 0 | 126 | 11549 | 4134 | 0 |
| destroy | RANDOM_REMOVAL | 6944 | 3 | 145 | 675 | 6121 | 2 |
| destroy | RELATED_SHIFT_REMOVAL | 17350 | 4 | 141 | 11385 | 5820 | 2 |
| repair | FAIRNESS_AWARE_REGRET_2_REPAIR | 17309 | 0 | 318 | 12865 | 4126 | 0 |
| repair | GREEDY_REPAIR | 8113 | 2 | 94 | 4391 | 3626 | 0 |
| repair | REGRET_2_REPAIR | 7925 | 3 | 119 | 3913 | 3890 | 3 |
| repair | RELATION_AWARE_REPAIR | 24065 | 2 | 199 | 15305 | 8559 | 1 |

## 판정 경계

이 문서는 테스트 전용 튜닝 리더를 보고할 뿐 기본 활성화나 승격을 선언하지 않는다. Phase 0의 사용자 합의 수치 gate와 새 blind holdout이 필요하다.
