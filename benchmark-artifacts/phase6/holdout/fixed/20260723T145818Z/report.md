# Phase 6 paired benchmark

- 환경: `macos-local`
- partition: `holdout`
- run label: `phase6-POJO_OPTA_STYLE_CHANGE_SWAP-holdout-fixed-20260723T145818Z`
- candidate engine: `POJO_OPTA_STYLE_CHANGE_SWAP`
- operator set: `OPTA_STYLE_CHANGE_SWAP_ONLY`
- quantile: nearest-rank, 보간 없음
- 실행 순서: 입력/seed/repeat/profile마다 교차
- warm-up: 실행, 집계 제외
- fixed-evaluation 단위: OptaPlanner는 score calculation, POJO 후보는 candidate evaluation이므로 엔진 간 throughput으로 직접 비교하지 않음

## 품질·tail 요약

| profile | dataset | pairs | feasible Opta/POJO | W/T/L | Opta score p10/median/p90 | POJO score p10/median/p90 | paired delta p10/median/p90 | elapsed p95 Opta/POJO | eval p10/p50/p90 Opta/POJO | best eval p50/p90 POJO | rollback fail |
|---|---|---:|---:|---:|---|---|---|---:|---|---:|---:|
| fixed-evaluations | ALL | 80 | 80/80 | 24/44/12 | [0]hard/[-960/-5760/-5791/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-5279/0]soft | [0]hard/[0/-2880/-6891/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-5279/0]soft | [0, 0, 0, -1216, 0] / [0, 0, 0, 0, 0] / [0, 0, 3840, 0, 0] | 59792/60117 | 2296836/2296836/2296836 / 381210/381210/381210 | 39515/346767 | 0 |
| fixed-evaluations | fairness.json | 20 | 20/20 | 4/4/12 | [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7626/0]soft | [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7626/0]soft | [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] / [0, 0, 0, 2, 0] | 61491/60311 | 2296836/2296836/2296836 / 381210/381210/381210 | 39515/192708 | 0 |
| fixed-evaluations | preceptor.json | 20 | 20/20 | 20/0/0 | [0]hard/[-1920/-4800/-5819/0]soft / [0]hard/[0/-7680/-5985/0]soft / [0]hard/[0/-4800/-6005/0]soft | [0]hard/[0/-3840/-7119/0]soft / [0]hard/[0/-2880/-6849/0]soft / [0]hard/[0/-1440/-7063/0]soft | [0, 0, 960, -1334, 0] / [0, 0, 3840, -1172, 0] / [0, 960, 4800, -906, 0] | 58758/50247 | 2296836/2296836/2296836 / 381210/381210/381210 | 345832/369502 | 0 |
| fixed-evaluations | request.json | 20 | 20/20 | 0/20/0 | [0]hard/[0/0/-5279/0]soft / [0]hard/[0/0/-5279/0]soft / [0]hard/[0/0/-5279/0]soft | [0]hard/[0/0/-5279/0]soft / [0]hard/[0/0/-5279/0]soft / [0]hard/[0/0/-5279/0]soft | [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] | 54227/51237 | 2296836/2296836/2296836 / 381210/381210/381210 | 32680/141633 | 0 |
| fixed-evaluations | sample.json | 20 | 20/20 | 0/20/0 | [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft | [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft | [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] | 58044/55191 | 2296836/2296836/2296836 / 381210/381210/381210 | 4051/13966 | 0 |

점수 분위수는 전체 `RosterScore`를 사전식으로 정렬해 실제 vector를 선택합니다. paired delta 배열은 `[hard, soft0, soft1, soft2, soft3]`의 `POJO - OptaPlanner` 좌표별 nearest-rank입니다.

## 탐색 선택 분포·best 기여

| move | evaluated | accepted step | rejected | global best events | final best contribution runs |
|---|---:|---:|---:|---:|---:|
| REASSIGN | 15667104 | 469024 | 15198080 | 694 | 70 |
| SWAP | 14829696 | 244562 | 14585134 | 176 | 10 |

- history length: `400`
- full verification interval: `1000`
- cancelled candidates: `0`
- full verifications: `743390`

## 수치 결정 체크리스트

이 보고서는 승인 수치를 자동으로 만들지 않습니다. holdout 결과를 근거로 다음 값을 후속 결정해야 합니다.

- paired loss 허용 개수 또는 최소 non-loss 비율
- 상위 soft level별 p10 허용 열화량
- best 도달 평가 횟수의 최소 개선량
- wall-clock p95 상대/절대 허용 한계
- rollback failure 및 score mismatch 허용 건수
- 모든 운영 입력·seed의 hard score `0` 요구 유지 여부

Execution failures: 0
