# Phase 6 paired benchmark

- 환경: `macos-local`
- partition: `research-hybrid-dev`
- run label: `phase6-POJO_HYBRID_OPTA_THEN_ALNS-research-hybrid-dev-fixed-20260724T042106Z`
- candidate engine: `POJO_HYBRID_OPTA_THEN_ALNS`
- operator set: `OPTA_STYLE_THEN_ALNS_SEQUENTIAL`
- quantile: nearest-rank, 보간 없음
- 실행 순서: 입력/seed/repeat/profile마다 교차
- warm-up: 미실행
- fixed-evaluation 단위: OptaPlanner는 score calculation, POJO 후보는 candidate evaluation이므로 엔진 간 throughput으로 직접 비교하지 않음

## 품질·tail 요약

| profile | dataset | pairs | feasible Opta/POJO | W/T/L | Opta score p10/median/p90 | POJO score p10/median/p90 | paired delta p10/median/p90 | elapsed p95 Opta/POJO | eval p10/p50/p90 Opta/POJO | best eval p50/p90 POJO | rollback fail |
|---|---|---:|---:|---:|---|---|---|---:|---|---:|---:|
| fixed-evaluations | ALL | 8 | 6/8 | 2/3/3 | [-8]hard/[0/-10560/-9335/0]soft / [0]hard/[0/0/-7630/0]soft / [0]hard/[0/0/-5281/0]soft | [0]hard/[-480/-6720/-7343/0]soft / [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-5283/0]soft | [0, -480, 0, -2, 0] / [0, 0, 0, 0, 0] / [8, 0, 6240, 1992, 0] | 503/4000 | 10000/10000/10000 / 100/100/100 | 100/100 | 0 |
| fixed-evaluations | fairness.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-7630/0]soft / [0]hard/[0/0/-7630/0]soft / [0]hard/[0/0/-7630/0]soft | [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft | [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] | 503/2698 | 10000/10000/10000 / 100/100/100 | 100/100 | 0 |
| fixed-evaluations | preceptor.json | 2 | 0/2 | 2/0/0 | [-8]hard/[0/-10560/-9335/0]soft / [-8]hard/[0/-10560/-9335/0]soft / [-7]hard/[0/-11520/-9387/0]soft | [0]hard/[-480/-6720/-7343/0]soft / [0]hard/[-480/-6720/-7343/0]soft / [0]hard/[0/-5280/-7441/0]soft | [7, -480, 3840, 1946, 0] / [7, -480, 3840, 1946, 0] / [8, 0, 6240, 1992, 0] | 327/4000 | 10000/10000/10000 / 100/100/100 | 100/100 | 0 |
| fixed-evaluations | request.json | 2 | 2/2 | 0/1/1 | [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5281/0]soft | [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5283/0]soft | [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] / [0, 0, 0, 0, 0] | 156/2091 | 10000/10000/10000 / 100/100/100 | 100/100 | 0 |
| fixed-evaluations | sample.json | 2 | 2/2 | 0/2/0 | [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft | [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft | [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] | 163/2280 | 10000/10000/10000 / 100/100/100 | 100/100 | 0 |

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
