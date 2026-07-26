# Phase 6 SA/ALNS 하이퍼파라미터 레이싱

- 생성 시각: `2026-07-24T02:51:08.050639Z`
- production 기본값 변경: 없음
- promotion gate 적용: 없음
- 훈련 seed: `[201]`
- 검증 seed: `[301]`
- 잠긴 기존 holdout 101..110: 설정 선택에 사용하지 않음
- fixed-evaluation: OptaPlanner score calculation과 ALNS complete candidate evaluation은 서로 다른 단위이므로 throughput 비교로 해석하지 않음
- 설정 선택: case별 사전식 feasible → pairwise 승 → 패 → ordinal rank sum; 수치 promotion gate가 아님

## 단계별 리더

| 단계 | partition | 후보 | case | eval | 1위 | feasible | pair W/T/L | rank sum | global best |
|---|---|---:|---:|---:|---|---:|---:|---:|---:|
| 01-sa | training | 16 | 1 | 20 | `p0_35-r0_001-c64-d0_05-q8-a2-x0_2-s100-foff` | 1 | 0/15/0 | 1 | 0 |
| 02-calibration | training | 7 | 1 | 20 | `p0_35-r0_005-c32-d0_05-q8-a2-x0_2-s100-foff` | 1 | 0/6/0 | 1 | 0 |
| 03-destroy | training | 25 | 1 | 20 | `p0_35-r0_001-c32-d0_05-q4-a2-x0_2-s100-foff` | 1 | 0/24/0 | 1 | 0 |
| 04-repair | training | 7 | 1 | 20 | `p0_35-r0_005-c32-d0_12-q4-a1-x0_2-s100-foff` | 1 | 0/6/0 | 1 | 0 |
| 05-adaptive | training | 25 | 1 | 20 | `p0_35-r0_005-c32-d0_12-q4-a2-x0_4-s100-foff` | 1 | 0/24/0 | 1 | 0 |
| 06-operator-set | training | 7 | 1 | 20 | `p0_35-r0_005-c32-d0_12-q4-a1-x0_05-s50-foff` | 1 | 0/6/0 | 1 | 0 |
| 07-validation | validation | 3 | 1 | 50 | `p0_35-r0_005-c32-d0_12-q4-a2-x0_4-s100-foff` | 1 | 0/2/0 | 1 | 0 |

## 검증 후 비교 후보

1. `p0_35-r0_005-c32-d0_12-q4-a2-x0_4-s100-foff`: `SearchConfig[initialAcceptanceProbability=0.35, finalTemperatureRatio=0.005, calibrationAttempts=32, destroyRate=0.12, qMax=4, maxRepairAttempts=2, reactionFactor=0.4, segmentLength=100, fairnessOperator=false]`

## OptaPlanner paired 비교

| profile | config | pairs | feasible Opta/ALNS | W/T/L | Opta score p10/median/p90 | ALNS score p10/median/p90 | paired delta p10/median/p90 | p95 ms Opta/ALNS | best eval p50/p90 Opta | best eval p50/p90 ALNS | rollback attempt/fail |
|---|---|---:|---:|---:|---|---|---|---:|---|---|---:|
| fixed-evaluations | `p0_35-r0_005-c32-d0_12-q4-a2-x0_4-s100-foff` | 1 | 1/1 | 0/0/1 | [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7628/0]soft | [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft | [0, 0, 0, -4, 0] / [0, 0, 0, -4, 0] / [0, 0, 0, -4, 0] | 783/83 | 11754/11754 | 0/0 | 34/0 |

## 연산자 선택·기여

### fixed-evaluations / `p0_35-r0_005-c32-d0_12-q4-a2-x0_4-s100-foff`

| 종류 | operator | 선택 | global best | current 개선 | 악화 수락 | 거절 | final best |
|---|---|---:|---:|---:|---:|---:|---:|
| destroy | PRECEPTOR_RELATION_GROUP_REMOVAL | 18 | 0 | 0 | 3 | 15 | 0 |
| destroy | RANDOM_REMOVAL | 16 | 0 | 0 | 6 | 10 | 0 |
| destroy | RELATED_SHIFT_REMOVAL | 16 | 0 | 0 | 7 | 9 | 0 |
| repair | GREEDY_REPAIR | 14 | 0 | 0 | 6 | 8 | 0 |
| repair | REGRET_2_REPAIR | 5 | 0 | 0 | 2 | 3 | 0 |
| repair | RELATION_AWARE_REPAIR | 31 | 0 | 0 | 8 | 23 | 0 |

## 판정 경계

이 문서는 테스트 전용 튜닝 리더를 보고할 뿐 기본 활성화나 승격을 선언하지 않는다. Phase 0의 사용자 합의 수치 gate와 새 blind holdout이 필요하다.
