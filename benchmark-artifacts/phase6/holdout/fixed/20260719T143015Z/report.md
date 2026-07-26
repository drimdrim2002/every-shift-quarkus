# Phase 6 baseline paired benchmark

- 환경: `macos-local`
- partition: `holdout`
- run label: `phase6-baseline-holdout-fixed-20260719T143015Z`
- operator set: `BASELINE_ONLY`
- quantile: nearest-rank, 보간 없음
- 실행 순서: 입력/seed/repeat/profile마다 교차
- warm-up: 실행, 집계 제외
- fixed-evaluation 단위: OptaPlanner는 score calculation, POJO_ALNS는 complete candidate이므로 엔진 간 throughput으로 직접 비교하지 않음

## 품질·tail 요약

| profile | dataset | pairs | feasible Opta/POJO | W/T/L | Opta score p10/median/p90 | POJO score p10/median/p90 | paired delta p10/median/p90 | elapsed p95 Opta/POJO | eval p10/p50/p90 Opta/POJO | best eval p50/p90 POJO | rollback fail |
|---|---|---:|---:|---:|---|---|---|---:|---|---:|---:|
| fixed-evaluations | ALL | 80 | 80/80 | 16/44/20 | [0]hard/[-960/-5760/-5791/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-5279/0]soft | [0]hard/[0/-7200/-6971/0]soft / [0]hard/[0/0/-7624/0]soft / [0]hard/[0/0/-5279/0]soft | [0, 0, -480, -1242, 0] / [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] | 59747/58388 | 2206174/2206174/2206174 / 29952/29952/29952 | 15849/25864 | 0 |
| fixed-evaluations | fairness.json | 20 | 20/20 | 4/8/8 | [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7626/0]soft | [0]hard/[0/0/-7630/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7626/0]soft | [0, 0, 0, -4, 0] / [0, 0, 0, 0, 0] / [0, 0, 0, 2, 0] | 60247/58612 | 2206174/2206174/2206174 / 29952/29952/29952 | 23931/28120 | 0 |
| fixed-evaluations | preceptor.json | 20 | 20/20 | 12/0/8 | [0]hard/[-1920/-4800/-5819/0]soft / [0]hard/[0/-7680/-5987/0]soft / [0]hard/[0/-4800/-6007/0]soft | [0]hard/[-480/-6720/-7113/0]soft / [0]hard/[0/-6720/-7015/0]soft / [0]hard/[0/-5760/-7029/0]soft | [0, 0, -1920, -1294, 0] / [0, 0, 0, -1100, 0] / [0, 960, 480, -694, 0] | 58920/32553 | 2206174/2206174/2206174 / 29952/29952/29952 | 0/16068 | 0 |
| fixed-evaluations | request.json | 20 | 20/20 | 0/20/0 | [0]hard/[0/0/-5279/0]soft / [0]hard/[0/0/-5279/0]soft / [0]hard/[0/0/-5279/0]soft | [0]hard/[0/0/-5279/0]soft / [0]hard/[0/0/-5279/0]soft / [0]hard/[0/0/-5279/0]soft | [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] | 53694/50704 | 2206174/2206174/2206174 / 29952/29952/29952 | 12030/15849 | 0 |
| fixed-evaluations | sample.json | 20 | 20/20 | 0/16/4 | [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft | [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft | [0, 0, 0, -2, 0] / [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] | 57362/51471 | 2206174/2206174/2206174 / 29952/29952/29952 | 16888/24287 | 0 |

점수 분위수는 전체 `RosterScore`를 사전식으로 정렬해 실제 vector를 선택합니다. paired delta 배열은 `[hard, soft0, soft1, soft2, soft3]`의 `POJO - OptaPlanner` 좌표별 nearest-rank입니다.

## ALNS operator 선택·best 기여

### Destroy

| operator | selections | global best events | current improvements | accepted worsening | rejections | final best contribution runs |
|---|---:|---:|---:|---:|---:|---:|
| PRECEPTOR_RELATION_GROUP_REMOVAL | 666160 | 2 | 5318 | 441184 | 219656 | 0 |
| RANDOM_REMOVAL | 288842 | 18 | 5868 | 11628 | 271328 | 10 |
| RELATED_SHIFT_REMOVAL | 1441158 | 86 | 13946 | 1033764 | 393362 | 50 |

### Repair

| operator | selections | global best events | current improvements | accepted worsening | rejections | final best contribution runs |
|---|---:|---:|---:|---:|---:|---:|
| GREEDY_REPAIR | 623110 | 38 | 6252 | 409216 | 207604 | 22 |
| REGRET_2_REPAIR | 571556 | 38 | 8782 | 326998 | 235738 | 20 |
| RELATION_AWARE_REPAIR | 1201494 | 30 | 10098 | 750362 | 441004 | 18 |

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
