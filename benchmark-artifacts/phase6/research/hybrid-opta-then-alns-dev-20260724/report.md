# Phase 6 paired benchmark

- 환경: `macos-local`
- partition: `research-hybrid-dev`
- run label: `phase6-POJO_HYBRID_OPTA_THEN_ALNS-research-hybrid-dev-wall-clock-20260724T042013Z`
- candidate engine: `POJO_HYBRID_OPTA_THEN_ALNS`
- operator set: `OPTA_STYLE_THEN_ALNS_SEQUENTIAL`
- quantile: nearest-rank, 보간 없음
- 실행 순서: 입력/seed/repeat/profile마다 교차
- warm-up: 미실행
- fixed-evaluation 단위: OptaPlanner는 score calculation, POJO 후보는 candidate evaluation이므로 엔진 간 throughput으로 직접 비교하지 않음

## 품질·tail 요약

| profile | dataset | pairs | feasible Opta/POJO | W/T/L | Opta score p10/median/p90 | POJO score p10/median/p90 | paired delta p10/median/p90 | elapsed p95 Opta/POJO | eval p10/p50/p90 Opta/POJO | best eval p50/p90 POJO | rollback fail |
|---|---|---:|---:|---:|---|---|---|---:|---|---:|---:|
| wall-clock | ALL | 8 | 6/6 | 0/0/8 | [-4]hard/[-3360/-15840/-6123/0]soft / [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-5281/0]soft | [-720]hard/[0/-1440/-6129/0]soft / [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-5283/0]soft | [-716, 0, 0, -6, 0] / [0, 0, 0, -2, 0] / [0, 3360, 14400, 28, 0] | 2004/2257 | 67836/83582/89379 / 0/0/0 | 0/0 | 0 |
| wall-clock | fairness.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7628/0]soft | [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft | [0, 0, 0, -4, 0] / [0, 0, 0, -4, 0] / [0, 0, 0, -4, 0] | 2004/2177 | 67836/67836/83582 / 0/0/0 | 0/0 | 0 |
| wall-clock | preceptor.json | 2 | 0/0 | 0/0/2 | [-4]hard/[-3360/-15840/-6123/0]soft / [-4]hard/[-3360/-15840/-6123/0]soft / [-4]hard/[-3360/-11520/-6157/0]soft | [-720]hard/[0/-1440/-6129/0]soft / [-720]hard/[0/-1440/-6129/0]soft / [-720]hard/[0/-1440/-6129/0]soft | [-716, 3360, 10080, -6, 0] / [-716, 3360, 10080, -6, 0] / [-716, 3360, 14400, 28, 0] | 2000/2257 | 72340/72340/76722 / 0/0/0 | 0/0 | 0 |
| wall-clock | request.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5281/0]soft | [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5283/0]soft | [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] | 2000/1821 | 86349/86349/89379 / 0/0/0 | 0/0 | 0 |
| wall-clock | sample.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft | [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft | [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] | 1999/2003 | 85552/85552/86022 / 0/0/0 | 0/0 | 0 |

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
