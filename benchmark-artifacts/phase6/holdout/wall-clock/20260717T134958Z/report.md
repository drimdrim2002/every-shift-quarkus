# Phase 6 baseline paired benchmark

- 환경: `macos-local`
- partition: `holdout`
- run label: `phase6-baseline-holdout-wall-clock-20260717T134958Z`
- operator set: `BASELINE_ONLY`
- quantile: nearest-rank, 보간 없음
- 실행 순서: 입력/seed/repeat/profile마다 교차
- warm-up: 실행, 집계 제외
- fixed-evaluation 단위: OptaPlanner는 score calculation, POJO_ALNS는 complete candidate이므로 엔진 간 throughput으로 직접 비교하지 않음

## 품질·tail 요약

| profile | dataset | pairs | feasible Opta/POJO | W/T/L | Opta score p10/median/p90 | POJO score p10/median/p90 | paired delta p10/median/p90 | elapsed p95 Opta/POJO | eval p10/p50/p90 Opta/POJO | best eval p50/p90 POJO | rollback fail |
|---|---|---:|---:|---:|---|---|---|---:|---|---:|---:|
| wall-clock | ALL | 80 | 80/80 | 21/47/12 | [0]hard/[-960/-5760/-5791/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-5279/0]soft | [0]hard/[0/-7200/-6971/0]soft / [0]hard/[0/0/-7624/0]soft / [0]hard/[0/0/-5279/0]soft | [0, 0, -480, -1242, 0] / [0, 0, 0, 0, 0] / [0, 0, 0, 2, 0] | 60000/59992 | 2206174/2271453/2437572 / 29952/35684/60878 | 9866/28327 | 0 |
| wall-clock | fairness.json | 20 | 20/20 | 9/7/4 | [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7626/0]soft | [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7624/0]soft | [0, 0, 0, -6, 0] / [0, 0, 0, 0, 0] / [0, 0, 0, 2, 0] | 60000/59992 | 2142324/2233704/2249001 / 29275/29980/30138 | 21259/28428 | 0 |
| wall-clock | preceptor.json | 20 | 20/20 | 12/0/8 | [0]hard/[-1920/-4800/-5819/0]soft / [0]hard/[0/-7680/-5985/0]soft / [0]hard/[0/-4800/-6005/0]soft | [0]hard/[-480/-6720/-7113/0]soft / [0]hard/[0/-6720/-7015/0]soft / [0]hard/[0/-5760/-6981/0]soft | [0, 0, -1920, -1294, 0] / [0, 0, 0, -1052, 0] / [0, 960, 480, -694, 0] | 60000/59992 | 2188057/2265012/2317844 / 59949/60861/61849 | 0/40269 | 0 |
| wall-clock | request.json | 20 | 20/20 | 0/20/0 | [0]hard/[0/0/-5279/0]soft / [0]hard/[0/0/-5279/0]soft / [0]hard/[0/0/-5279/0]soft | [0]hard/[0/0/-5279/0]soft / [0]hard/[0/0/-5279/0]soft / [0]hard/[0/0/-5279/0]soft | [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] | 60000/59991 | 2354548/2432591/2446090 / 35304/35684/35924 | 5643/8118 | 0 |
| wall-clock | sample.json | 20 | 20/20 | 0/20/0 | [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft | [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft | [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] | 60000/59992 | 2215320/2272259/2284017 / 35308/35630/35966 | 12047/16176 | 0 |

점수 분위수는 전체 `RosterScore`를 사전식으로 정렬해 실제 vector를 선택합니다. paired delta 배열은 `[hard, soft0, soft1, soft2, soft3]`의 `POJO - OptaPlanner` 좌표별 nearest-rank입니다.

## ALNS operator 선택·best 기여

### Destroy

| operator | selections | global best events | current improvements | accepted worsening | rejections | final best contribution runs |
|---|---:|---:|---:|---:|---:|---:|
| PRECEPTOR_RELATION_GROUP_REMOVAL | 1119016 | 10 | 2228 | 888898 | 227880 | 4 |
| RANDOM_REMOVAL | 333671 | 16 | 2544 | 9527 | 321584 | 12 |
| RELATED_SHIFT_REMOVAL | 1789019 | 97 | 4896 | 1230544 | 553482 | 48 |

### Repair

| operator | selections | global best events | current improvements | accepted worsening | rejections | final best contribution runs |
|---|---:|---:|---:|---:|---:|---:|
| GREEDY_REPAIR | 747454 | 29 | 2470 | 487714 | 257241 | 15 |
| REGRET_2_REPAIR | 659001 | 46 | 3200 | 367467 | 288288 | 27 |
| RELATION_AWARE_REPAIR | 1835251 | 48 | 3998 | 1273788 | 557417 | 22 |

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
