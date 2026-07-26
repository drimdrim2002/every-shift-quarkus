# Phase 6 paired benchmark

- 환경: `macos-local`
- partition: `smoke`
- run label: `phase6-POJO_OPTA_STYLE_CHANGE_SWAP-smoke-wall-clock-20260723T114826Z`
- candidate engine: `POJO_OPTA_STYLE_CHANGE_SWAP`
- operator set: `OPTA_STYLE_CHANGE_SWAP_ONLY`
- quantile: nearest-rank, 보간 없음
- 실행 순서: 입력/seed/repeat/profile마다 교차
- warm-up: 미실행
- fixed-evaluation 단위: OptaPlanner는 score calculation, POJO 후보는 candidate evaluation이므로 엔진 간 throughput으로 직접 비교하지 않음

## 품질·tail 요약

| profile | dataset | pairs | feasible Opta/POJO | W/T/L | Opta score p10/median/p90 | POJO score p10/median/p90 | paired delta p10/median/p90 | elapsed p95 Opta/POJO | eval p10/p50/p90 Opta/POJO | best eval p50/p90 POJO | rollback fail |
|---|---|---:|---:|---:|---|---|---|---:|---|---:|---:|
| wall-clock | ALL | 1 | 1/1 | 1/0/0 | [0]hard/[-960/-6720/-5755/0]soft / [0]hard/[-960/-6720/-5755/0]soft / [0]hard/[-960/-6720/-5755/0]soft | [0]hard/[0/-2400/-6789/0]soft / [0]hard/[0/-2400/-6789/0]soft / [0]hard/[0/-2400/-6789/0]soft | [0, 960, 4320, -1034, 0] / [0, 960, 4320, -1034, 0] / [0, 960, 4320, -1034, 0] | 60007/60001 | 2175092/2175092/2175092 / 474282/474282/474282 | 441295/441295 | 0 |
| wall-clock | preceptor.json | 1 | 1/1 | 1/0/0 | [0]hard/[-960/-6720/-5755/0]soft / [0]hard/[-960/-6720/-5755/0]soft / [0]hard/[-960/-6720/-5755/0]soft | [0]hard/[0/-2400/-6789/0]soft / [0]hard/[0/-2400/-6789/0]soft / [0]hard/[0/-2400/-6789/0]soft | [0, 960, 4320, -1034, 0] / [0, 960, 4320, -1034, 0] / [0, 960, 4320, -1034, 0] | 60007/60001 | 2175092/2175092/2175092 / 474282/474282/474282 | 441295/441295 | 0 |

점수 분위수는 전체 `RosterScore`를 사전식으로 정렬해 실제 vector를 선택합니다. paired delta 배열은 `[hard, soft0, soft1, soft2, soft3]`의 `POJO - OptaPlanner` 좌표별 nearest-rank입니다.

## 탐색 선택 분포·best 기여

| move | evaluated | accepted step | rejected | global best events | final best contribution runs |
|---|---:|---:|---:|---:|---:|
| REASSIGN | 244131 | 5947 | 238184 | 40 | 0 |
| SWAP | 230151 | 3111 | 227040 | 9 | 1 |

- history length: `400`
- full verification interval: `1000`
- cancelled candidates: `0`
- full verifications: `9523`

## 수치 결정 체크리스트

이 보고서는 승인 수치를 자동으로 만들지 않습니다. holdout 결과를 근거로 다음 값을 후속 결정해야 합니다.

- paired loss 허용 개수 또는 최소 non-loss 비율
- 상위 soft level별 p10 허용 열화량
- best 도달 평가 횟수의 최소 개선량
- wall-clock p95 상대/절대 허용 한계
- rollback failure 및 score mismatch 허용 건수
- 모든 운영 입력·seed의 hard score `0` 요구 유지 여부

Execution failures: 0
