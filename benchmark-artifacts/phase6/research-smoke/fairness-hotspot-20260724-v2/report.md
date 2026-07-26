# Phase 6 paired benchmark

- 환경: `macos-local`
- partition: `research-smoke`
- run label: `phase6-POJO_ALNS_FAIRNESS_HOTSPOT-research-smoke-quick-20260724T015241Z`
- candidate engine: `POJO_ALNS_FAIRNESS_HOTSPOT`
- operator set: `BASELINE_PLUS_FAIRNESS_HOTSPOT_PAIR`
- quantile: nearest-rank, 보간 없음
- 실행 순서: 입력/seed/repeat/profile마다 교차
- warm-up: 미실행
- fixed-evaluation 단위: OptaPlanner는 score calculation, POJO 후보는 candidate evaluation이므로 엔진 간 throughput으로 직접 비교하지 않음

## 품질·tail 요약

| profile | dataset | pairs | feasible Opta/POJO | W/T/L | Opta score p10/median/p90 | POJO score p10/median/p90 | paired delta p10/median/p90 | elapsed p95 Opta/POJO | eval p10/p50/p90 Opta/POJO | best eval p50/p90 POJO | rollback fail |
|---|---|---:|---:|---:|---|---|---|---:|---|---:|---:|
| wall-clock | ALL | 1 | 1/1 | 0/0/1 | [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7626/0]soft | [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft | [0, 0, 0, -6, 0] / [0, 0, 0, -6, 0] / [0, 0, 0, -6, 0] | 5004/4992 | 187273/187273/187273 / 1014/1014/1014 | 0/0 | 0 |
| wall-clock | fairness.json | 1 | 1/1 | 0/0/1 | [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7626/0]soft | [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft | [0, 0, 0, -6, 0] / [0, 0, 0, -6, 0] / [0, 0, 0, -6, 0] | 5004/4992 | 187273/187273/187273 / 1014/1014/1014 | 0/0 | 0 |
| fixed-evaluations | ALL | 1 | 1/1 | 0/0/1 | [0]hard/[0/0/-7630/0]soft / [0]hard/[0/0/-7630/0]soft / [0]hard/[0/0/-7630/0]soft | [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft | [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] | 423/2590 | 20000/20000/20000 / 200/200/200 | 0/0 | 0 |
| fixed-evaluations | fairness.json | 1 | 1/1 | 0/0/1 | [0]hard/[0/0/-7630/0]soft / [0]hard/[0/0/-7630/0]soft / [0]hard/[0/0/-7630/0]soft | [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft | [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] | 423/2590 | 20000/20000/20000 / 200/200/200 | 0/0 | 0 |

점수 분위수는 전체 `RosterScore`를 사전식으로 정렬해 실제 vector를 선택합니다. paired delta 배열은 `[hard, soft0, soft1, soft2, soft3]`의 `POJO - OptaPlanner` 좌표별 nearest-rank입니다.

## 탐색 선택 분포·best 기여

### Destroy

| operator | selections | global best events | current improvements | accepted worsening | rejections | final best contribution runs |
|---|---:|---:|---:|---:|---:|---:|
| FAIRNESS_HOTSPOT_REMOVAL | 377 | 0 | 32 | 174 | 171 | 0 |
| PRECEPTOR_RELATION_GROUP_REMOVAL | 222 | 0 | 5 | 16 | 201 | 0 |
| RANDOM_REMOVAL | 239 | 0 | 10 | 12 | 217 | 0 |
| RELATED_SHIFT_REMOVAL | 376 | 0 | 11 | 290 | 75 | 0 |

### Repair

| operator | selections | global best events | current improvements | accepted worsening | rejections | final best contribution runs |
|---|---:|---:|---:|---:|---:|---:|
| FAIRNESS_AWARE_REGRET_2_REPAIR | 377 | 0 | 32 | 174 | 171 | 0 |
| GREEDY_REPAIR | 218 | 0 | 6 | 110 | 102 | 0 |
| REGRET_2_REPAIR | 225 | 0 | 9 | 97 | 119 | 0 |
| RELATION_AWARE_REPAIR | 394 | 0 | 11 | 111 | 272 | 0 |

`final best contribution runs`는 해당 run의 마지막 global-best 개선을 만든 operator가 이 operator였던 실행 수입니다.

## 수치 결정 체크리스트

이 보고서는 승인 수치를 자동으로 만들지 않습니다. holdout 결과를 근거로 다음 값을 후속 결정해야 합니다.

- paired loss 허용 개수 또는 최소 non-loss 비율
- 상위 soft level별 p10 허용 열화량
- best 도달 평가 횟수의 최소 개선량
- wall-clock p95 상대/절대 허용 한계
- rollback failure 및 score mismatch 허용 건수
- 모든 운영 입력·seed의 hard score `0` 요구 유지 여부

Execution failures: 0
