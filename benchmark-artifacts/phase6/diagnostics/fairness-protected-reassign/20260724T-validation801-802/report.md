# Phase 6 paired benchmark

- 환경: `macos-local`
- partition: `research-fairness-hotspot-validation`
- run label: `fairness-hotspot-protected-reassign-validation-20260724`
- candidate engine: `POJO_FAIRNESS_HOTSPOT_PROTECTED_REASSIGN`
- operator set: `FAIRNESS_HOTSPOT_GUIDED_PROTECTED_REASSIGN`
- quantile: nearest-rank, 보간 없음
- 실행 순서: 입력/seed/repeat/profile마다 교차
- warm-up: 실행, 집계 제외
- fixed-evaluation 단위: OptaPlanner는 score calculation, POJO 후보는 candidate evaluation이므로 엔진 간 throughput으로 직접 비교하지 않음

## 품질·tail 요약

| profile | dataset | pairs | feasible Opta/POJO | W/T/L | Opta score p10/median/p90 | POJO score p10/median/p90 | paired delta p10/median/p90 | elapsed p95 Opta/POJO | eval p10/p50/p90 Opta/POJO | best eval p50/p90 POJO | rollback fail |
|---|---|---:|---:|---:|---|---|---|---:|---|---:|---:|
| wall-clock | ALL | 8 | 6/8 | 3/1/4 | [-1]hard/[-1920/-9600/-6477/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-5279/0]soft | [0]hard/[0/-6240/-7261/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-5281/0]soft | [0, 0, 0, -1052, 0] / [0, 0, 0, -2, 0] / [1, 1920, 4800, 2, 0] | 5000/3771 | 192558/197139/215987 / 0/4/256 | 0/17 | 0 |
| wall-clock | fairness.json | 2 | 2/2 | 1/1/0 | [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7626/0]soft | [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7626/0]soft | [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] / [0, 0, 0, 2, 0] | 5000/2305 | 192558/192558/198790 / 24/24/24 | 17/17 | 0 |
| wall-clock | preceptor.json | 2 | 0/2 | 2/0/0 | [-1]hard/[-1920/-9600/-6477/0]soft / [-1]hard/[-1920/-9600/-6477/0]soft / [-1]hard/[-1440/-11040/-6209/0]soft | [0]hard/[0/-6240/-7261/0]soft / [0]hard/[0/-6240/-7261/0]soft / [0]hard/[0/-5280/-6781/0]soft | [1, 1440, 4320, -1052, 0] / [1, 1440, 4320, -1052, 0] / [1, 1920, 4800, -304, 0] | 5000/3771 | 193707/193707/196127 / 256/256/256 | 0/0 | 0 |
| wall-clock | request.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-5279/0]soft / [0]hard/[0/0/-5279/0]soft / [0]hard/[0/0/-5279/0]soft | [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5281/0]soft | [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] | 5000/1718 | 214069/214069/215987 / 4/4/4 | 4/4 | 0 |
| wall-clock | sample.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft | [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft | [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] | 5000/2018 | 197139/197139/198809 / 0/0/0 | 0/0 | 0 |
| fixed-evaluations | ALL | 8 | 6/8 | 4/2/2 | [-5]hard/[-5280/-13440/-6623/0]soft / [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-5281/0]soft | [0]hard/[0/-6240/-7261/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-5281/0]soft | [0, 0, 0, -422, 0] / [0, 0, 0, -2, 0] / [5, 5280, 8160, 2, 0] | 1298/3686 | 50000/50000/50000 / 0/4/256 | 0/17 | 0 |
| fixed-evaluations | fairness.json | 2 | 2/2 | 2/0/0 | [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7628/0]soft | [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7626/0]soft | [0, 0, 0, 2, 0] / [0, 0, 0, 2, 0] / [0, 0, 0, 2, 0] | 1182/2172 | 50000/50000/50000 / 24/24/24 | 17/17 | 0 |
| fixed-evaluations | preceptor.json | 2 | 0/2 | 2/0/0 | [-5]hard/[-5280/-13440/-6623/0]soft / [-5]hard/[-5280/-13440/-6623/0]soft / [-5]hard/[-2400/-12960/-6839/0]soft | [0]hard/[0/-6240/-7261/0]soft / [0]hard/[0/-6240/-7261/0]soft / [0]hard/[0/-5280/-6781/0]soft | [5, 2400, 6720, -422, 0] / [5, 2400, 6720, -422, 0] / [5, 5280, 8160, -158, 0] | 1298/3686 | 50000/50000/50000 / 256/256/256 | 0/0 | 0 |
| fixed-evaluations | request.json | 2 | 2/2 | 0/2/0 | [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5281/0]soft | [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5281/0]soft | [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] | 1094/1714 | 50000/50000/50000 / 4/4/4 | 4/4 | 0 |
| fixed-evaluations | sample.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft | [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft | [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] | 1150/2009 | 50000/50000/50000 / 0/0/0 | 0/0 | 0 |

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
