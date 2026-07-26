# Phase 6 POJO ALNS → Change/Swap → ordered VND 장기 benchmark

- 상태: test-only 후보. production 기본값·점수 의미·Phase 0 gate는 변경하지 않았습니다.
- profile: `fixed-evaluations`, partition/seed: `phase7-shadow-fixed-smoke` / `[1601, 1602]`
- warm-up: 실행 후 집계 제외
- Opta cache scope: `input_sha256/seed/profile/opta_budget`; 각 case의 Opta 결과 1개를 세 후보가 공유합니다.
- 비교: 엄격한 `hard > soft[0] > soft[1] > soft[2] > soft[3]`; W/T/L은 최초 차이 목적식으로 결정합니다.
- fixed 단위: Opta score calculation과 POJO candidate evaluation은 서로 달라 처리량 비교가 아닙니다.

## 품질·안전성

| 후보 | dataset | pairs | feasible Opta/POJO | W/T/L | POJO score p10/median/p90 | paired delta p10/median/p90 | soft2 delta p10/median/p90 | best eval p50/p90 | elapsed p95 Opta/POJO | rollback/mismatch/corruption |
|---|---|---:|---:|---:|---|---|---|---:|---:|---:|
| ALNS_THEN_ORDERED_VND_PRECEPTOR_PREFIX_REASSIGN | ALL | 8 | 0/8 | 0/0/0 | N/A / N/A / N/A | [0,0,0,0,0] / [0,0,0,0,0] / [0,0,0,0,0] | 0 / 0 / 0 | 0/498 | 318/5025 | 2868/0/0 |
| ALNS_THEN_ORDERED_VND_PRECEPTOR_PREFIX_REASSIGN | fairness.json | 2 | 0/2 | 0/0/0 | N/A / N/A / N/A | [0,0,0,0,0] / [0,0,0,0,0] / [0,0,0,0,0] | 0 / 0 / 0 | 0/0 | 318/3878 | 733/0/0 |
| ALNS_THEN_ORDERED_VND_PRECEPTOR_PREFIX_REASSIGN | preceptor.json | 2 | 0/2 | 0/0/0 | N/A / N/A / N/A | [0,0,0,0,0] / [0,0,0,0,0] / [0,0,0,0,0] | 0 / 0 / 0 | 400/498 | 217/5025 | 634/0/0 |
| ALNS_THEN_ORDERED_VND_PRECEPTOR_PREFIX_REASSIGN | request.json | 2 | 0/2 | 0/0/0 | N/A / N/A / N/A | [0,0,0,0,0] / [0,0,0,0,0] / [0,0,0,0,0] | 0 / 0 / 0 | 0/0 | 169/2543 | 778/0/0 |
| ALNS_THEN_ORDERED_VND_PRECEPTOR_PREFIX_REASSIGN | sample.json | 2 | 0/2 | 0/0/0 | N/A / N/A / N/A | [0,0,0,0,0] / [0,0,0,0,0] / [0,0,0,0,0] | 0 / 0 / 0 | 0/0 | 158/2817 | 723/0/0 |

## 최초 차이 목적식·prefix equality

- `ALNS_THEN_ORDERED_VND_PRECEPTOR_PREFIX_REASSIGN`: {"hard":[0,0,0],"soft0":[0,0,0],"soft1":[0,0,0],"soft2":[0,0,0],"soft3":[0,0,0],"tie":[0,0,0]}

## 단계·선택 분포

`raw.jsonl`의 candidate record에 ALNS destroy/repair, ordered VND Reassign/Swap, protected fairness selector, 각 stage 입력/출력 score·예약/사용/unused budget·termination을 분리 기록합니다. `summary.json`의 `candidate_distribution`은 이를 전체 집계합니다.

## 해석 경계

이 artifact는 Phase 0 수치 gate를 만들거나 promotion/default 활성화를 선언하지 않습니다. 120초 profile은 60초 Opta cache와 비교할 경우 extra-compute comparison으로만 해석해야 하며 equal-cost 승리라고 표현하지 않습니다.
