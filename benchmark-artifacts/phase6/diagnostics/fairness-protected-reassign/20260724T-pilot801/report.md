# Phase 6 paired benchmark

- 환경: `macos-local`
- partition: `research-fairness-hotspot-pilot`
- run label: `fairness-hotspot-protected-reassign-pilot-20260724`
- candidate engine: `POJO_FAIRNESS_HOTSPOT_PROTECTED_REASSIGN`
- operator set: `FAIRNESS_HOTSPOT_GUIDED_PROTECTED_REASSIGN`
- quantile: nearest-rank, 보간 없음
- 실행 순서: 입력/seed/repeat/profile마다 교차
- warm-up: 미실행
- fixed-evaluation 단위: OptaPlanner는 score calculation, POJO 후보는 candidate evaluation이므로 엔진 간 throughput으로 직접 비교하지 않음

## 품질·tail 요약

| profile | dataset | pairs | feasible Opta/POJO | W/T/L | Opta score p10/median/p90 | POJO score p10/median/p90 | paired delta p10/median/p90 | elapsed p95 Opta/POJO | eval p10/p50/p90 Opta/POJO | best eval p50/p90 POJO | rollback fail |
|---|---|---:|---:|---:|---|---|---|---:|---|---:|---:|
| wall-clock | ALL | 4 | 3/4 | 1/1/2 | [-2]hard/[-4800/-12000/-6851/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-5279/0]soft | [0]hard/[-480/-6720/-7149/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-5281/0]soft | [0, 0, 0, -298, 0] / [0, 0, 0, -2, 0] / [2, 4320, 5280, 0, 0] | 3005/3001 | 105921/119152/134823 / 0/0/24 | 0/17 | 0 |
| wall-clock | fairness.json | 1 | 1/1 | 0/1/0 | [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7626/0]soft | [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7626/0]soft | [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] | 3005/2219 | 105921/105921/105921 / 24/24/24 | 17/17 | 0 |
| wall-clock | preceptor.json | 1 | 0/1 | 1/0/0 | [-2]hard/[-4800/-12000/-6851/0]soft / [-2]hard/[-4800/-12000/-6851/0]soft / [-2]hard/[-4800/-12000/-6851/0]soft | [0]hard/[-480/-6720/-7149/0]soft / [0]hard/[-480/-6720/-7149/0]soft / [0]hard/[-480/-6720/-7149/0]soft | [2, 4320, 5280, -298, 0] / [2, 4320, 5280, -298, 0] / [2, 4320, 5280, -298, 0] | 2999/3001 | 119152/119152/119152 / 0/0/0 | 0/0 | 0 |
| wall-clock | request.json | 1 | 1/1 | 0/0/1 | [0]hard/[0/0/-5279/0]soft / [0]hard/[0/0/-5279/0]soft / [0]hard/[0/0/-5279/0]soft | [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5281/0]soft | [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] | 3000/1632 | 134823/134823/134823 / 4/4/4 | 4/4 | 0 |
| wall-clock | sample.json | 1 | 1/1 | 0/0/1 | [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft | [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft | [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] | 2999/1799 | 127917/127917/127917 / 0/0/0 | 0/0 | 0 |
| fixed-evaluations | ALL | 4 | 3/4 | 2/2/0 | [-8]hard/[0/-10560/-9595/0]soft / [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-5281/0]soft | [0]hard/[0/-5280/-6781/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-5281/0]soft | [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] / [8, 0, 5280, 2814, 0] | 194/3487 | 10000/10000/10000 / 0/4/256 | 0/17 | 0 |
| fixed-evaluations | fairness.json | 1 | 1/1 | 1/0/0 | [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7628/0]soft | [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7626/0]soft | [0, 0, 0, 2, 0] / [0, 0, 0, 2, 0] / [0, 0, 0, 2, 0] | 176/2037 | 10000/10000/10000 / 24/24/24 | 17/17 | 0 |
| fixed-evaluations | preceptor.json | 1 | 0/1 | 1/0/0 | [-8]hard/[0/-10560/-9595/0]soft / [-8]hard/[0/-10560/-9595/0]soft / [-8]hard/[0/-10560/-9595/0]soft | [0]hard/[0/-5280/-6781/0]soft / [0]hard/[0/-5280/-6781/0]soft / [0]hard/[0/-5280/-6781/0]soft | [8, 0, 5280, 2814, 0] / [8, 0, 5280, 2814, 0] / [8, 0, 5280, 2814, 0] | 194/3487 | 10000/10000/10000 / 256/256/256 | 0/0 | 0 |
| fixed-evaluations | request.json | 1 | 1/1 | 0/1/0 | [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5281/0]soft | [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5281/0]soft | [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] | 148/1541 | 10000/10000/10000 / 4/4/4 | 4/4 | 0 |
| fixed-evaluations | sample.json | 1 | 1/1 | 0/1/0 | [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft | [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft | [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] | 159/1801 | 10000/10000/10000 / 0/0/0 | 0/0 | 0 |

점수 분위수는 전체 `RosterScore`를 사전식으로 정렬해 실제 vector를 선택합니다. paired delta 배열은 `[hard, soft0, soft1, soft2, soft3]`의 `POJO - OptaPlanner` 좌표별 nearest-rank입니다.

## 탐색 선택 분포·best 기여

local-move metrics가 없습니다.

## 수치 결정 체크리스트

이 보고서는 승인 수치를 자동으로 만들지 않습니다. holdout 결과를 근거로 다음 값을 후속 결정해야 합니다.

- paired loss 허용 개수 또는 최소 non-loss 비율
- 상위 soft level별 p10 허용 열화량
- best 도달 평가 횟수의 최소 개선량
- wall-clock p95 상대/절대 허용 한계
- rollback failure 및 score mismatch 허용 건수
- 모든 운영 입력·seed의 hard score `0` 요구 유지 여부

Execution failures: 0
