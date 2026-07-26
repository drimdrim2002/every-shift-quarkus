# Phase 6 paired benchmark

- 환경: `macos-local`
- partition: `research-fairness-atomic-validation`
- run label: `fairness-atomic-witness-validation-20260724`
- candidate engine: `POJO_FAIRNESS_RESTRICTED_CHANGE_SWAP`
- operator set: `FAIRNESS_RESTRICTED_CHANGE_SWAP`
- quantile: nearest-rank, 보간 없음
- 실행 순서: 입력/seed/repeat/profile마다 교차
- warm-up: 실행, 집계 제외
- fixed-evaluation 단위: OptaPlanner는 score calculation, POJO 후보는 candidate evaluation이므로 엔진 간 throughput으로 직접 비교하지 않음

## 품질·tail 요약

| profile | dataset | pairs | feasible Opta/POJO | W/T/L | Opta score p10/median/p90 | POJO score p10/median/p90 | paired delta p10/median/p90 | elapsed p95 Opta/POJO | eval p10/p50/p90 Opta/POJO | best eval p50/p90 POJO | rollback fail |
|---|---|---:|---:|---:|---|---|---|---:|---|---:|---:|
| wall-clock | ALL | 2 | 2/2 | 2/0/0 | [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7628/0]soft | [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7624/0]soft | [0, 0, 0, 2, 0] / [0, 0, 0, 2, 0] / [0, 0, 0, 4, 0] | 9999/10000 | 386647/386647/387078 / 23090/23090/23956 | 11208/20078 | 0 |
| wall-clock | fairness.json | 2 | 2/2 | 2/0/0 | [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7628/0]soft | [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7624/0]soft | [0, 0, 0, 2, 0] / [0, 0, 0, 2, 0] / [0, 0, 0, 4, 0] | 9999/10000 | 386647/386647/387078 / 23090/23090/23956 | 11208/20078 | 0 |
| fixed-evaluations | ALL | 2 | 2/2 | 2/0/0 | [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7628/0]soft | [0]hard/[0/0/-7624/0]soft / [0]hard/[0/0/-7624/0]soft / [0]hard/[0/0/-7624/0]soft | [0, 0, 0, 4, 0] / [0, 0, 0, 4, 0] / [0, 0, 0, 4, 0] | 1178/18485 | 50000/50000/50000 / 50000/50000/50000 | 20078/24007 | 0 |
| fixed-evaluations | fairness.json | 2 | 2/2 | 2/0/0 | [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7628/0]soft | [0]hard/[0/0/-7624/0]soft / [0]hard/[0/0/-7624/0]soft / [0]hard/[0/0/-7624/0]soft | [0, 0, 0, 4, 0] / [0, 0, 0, 4, 0] / [0, 0, 0, 4, 0] | 1178/18485 | 50000/50000/50000 / 50000/50000/50000 | 20078/24007 | 0 |

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
