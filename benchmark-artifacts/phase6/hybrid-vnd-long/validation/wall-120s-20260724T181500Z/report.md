# Phase 6 POJO ALNS → Change/Swap → ordered VND 장기 benchmark

- 상태: test-only 후보. production 기본값·점수 의미·Phase 0 gate는 변경하지 않았습니다.
- profile: `wall-clock`, partition/seed: `validation-wall-120s` / `[1001, 1002]`
- warm-up: 실행 후 집계 제외
- Opta cache scope: `input_sha256/seed/profile/opta_budget`; 각 case의 Opta 결과 1개를 세 후보가 공유합니다.
- 비교: 엄격한 `hard > soft[0] > soft[1] > soft[2] > soft[3]`; W/T/L은 최초 차이 목적식으로 결정합니다.
- fixed 단위: Opta score calculation과 POJO candidate evaluation은 서로 달라 처리량 비교가 아닙니다.

## 품질·안전성

| 후보 | dataset | pairs | feasible Opta/POJO | W/T/L | POJO score p10/median/p90 | paired delta p10/median/p90 | soft2 delta p10/median/p90 | best eval p50/p90 | elapsed p95 Opta/POJO | rollback/mismatch/corruption |
|---|---|---:|---:|---:|---|---|---|---:|---:|---:|
| ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS | ALL | 8 | 8/8 | 4/4/0 | [0]hard/[0/-8160/-6979/0]soft / [0]hard/[0/0/-7622/0]soft / [0]hard/[0/0/-5279/0]soft | [0,0,-1920,-1116,0] / [0,0,0,0,0] / [0,960,0,4,0] | -1116 / 0 / 4 | 17076/90199 | 120152/99139 | 140263/0/0 |
| ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS | fairness.json | 2 | 2/2 | 2/0/0 | [0]hard/[0/0/-7622/0]soft / [0]hard/[0/0/-7622/0]soft / [0]hard/[0/0/-7622/0]soft | [0,0,0,4,0] / [0,0,0,4,0] / [0,0,0,4,0] | 4 / 4 / 4 | 35946/35972 | 120152/98785 | 29609/0/0 |
| ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS | preceptor.json | 2 | 2/2 | 2/0/0 | [0]hard/[0/-8160/-6979/0]soft / [0]hard/[0/-8160/-6979/0]soft / [0]hard/[0/-5760/-6765/0]soft | [0,960,-1920,-1116,0] / [0,960,-1920,-1116,0] / [0,960,-480,-924,0] | -1116 / -1116 / -924 | 89487/90199 | 120135/99139 | 42137/0/0 |
| ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS | request.json | 2 | 2/2 | 0/2/0 | [0]hard/[0/0/-5279/0]soft / [0]hard/[0/0/-5279/0]soft / [0]hard/[0/0/-5279/0]soft | [0,0,0,0,0] / [0,0,0,0,0] / [0,0,0,0,0] | 0 / 0 / 0 | 6677/8608 | 120112/97737 | 38031/0/0 |
| ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS | sample.json | 2 | 2/2 | 0/2/0 | [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft | [0,0,0,0,0] / [0,0,0,0,0] / [0,0,0,0,0] | 0 / 0 / 0 | 14976/17076 | 120114/98058 | 30486/0/0 |

## 최초 차이 목적식·prefix equality

- `ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS`: {"hard":[0,0,0],"soft0":[2,0,0],"soft1":[0,0,0],"soft2":[2,0,0],"soft3":[0,0,0],"tie":[0,4,0]}

## 단계·선택 분포

`raw.jsonl`의 candidate record에 ALNS destroy/repair, ordered VND Reassign/Swap, protected fairness selector, 각 stage 입력/출력 score·예약/사용/unused budget·termination을 분리 기록합니다. `summary.json`의 `candidate_distribution`은 이를 전체 집계합니다.

## 해석 경계

이 artifact는 Phase 0 수치 gate를 만들거나 promotion/default 활성화를 선언하지 않습니다. 120초 profile은 60초 Opta cache와 비교할 경우 extra-compute comparison으로만 해석해야 하며 equal-cost 승리라고 표현하지 않습니다.
