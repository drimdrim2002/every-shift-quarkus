# Phase 6 POJO ALNS → Change/Swap → ordered VND 장기 benchmark

- 상태: test-only 후보. production 기본값·점수 의미·Phase 0 gate는 변경하지 않았습니다.
- profile: `wall-clock`, partition/seed: `development-pilot-corrected` / `[901, 902]`
- warm-up: 실행 후 집계 제외
- Opta cache scope: `input_sha256/seed/profile/opta_budget`; 각 case의 Opta 결과 1개를 세 후보가 공유합니다.
- 비교: 엄격한 `hard > soft[0] > soft[1] > soft[2] > soft[3]`; W/T/L은 최초 차이 목적식으로 결정합니다.
- fixed 단위: Opta score calculation과 POJO candidate evaluation은 서로 달라 처리량 비교가 아닙니다.

## 품질·안전성

| 후보 | dataset | pairs | feasible Opta/POJO | W/T/L | POJO score p10/median/p90 | paired delta p10/median/p90 | soft2 delta p10/median/p90 | best eval p50/p90 | elapsed p95 Opta/POJO | rollback/mismatch/corruption |
|---|---|---:|---:|---:|---|---|---|---:|---:|---:|
| ALNS_ONLY | ALL | 8 | 6/8 | 2/0/6 | [0]hard/[-1440/-5760/-7005/0]soft / [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-5283/0]soft | [0,0,0,-638,0] / [0,0,0,-4,0] / [3,3360,8640,-2,0] | -638 / -4 / -2 | 0/0 | 3177/5808 | 4542/0/0 |
| ALNS_ONLY | fairness.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft | [0,0,0,-4,0] / [0,0,0,-4,0] / [0,0,0,-4,0] | -4 / -4 / -4 | 0/0 | 3177/5595 | 1127/0/0 |
| ALNS_ONLY | preceptor.json | 2 | 0/2 | 2/0/0 | [0]hard/[-1440/-5760/-7005/0]soft / [0]hard/[-1440/-5760/-7005/0]soft / [0]hard/[0/-4800/-6733/0]soft | [3,1440,7680,-638,0] / [3,1440,7680,-638,0] / [3,3360,8640,-626,0] | -638 / -638 / -626 | 0/0 | 3125/5808 | 769/0/0 |
| ALNS_ONLY | request.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5283/0]soft | [0,0,0,-2,0] / [0,0,0,-2,0] / [0,0,0,-2,0] | -2 / -2 / -2 | 0/0 | 3111/4606 | 1397/0/0 |
| ALNS_ONLY | sample.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft | [0,0,0,-2,0] / [0,0,0,-2,0] / [0,0,0,-2,0] | -2 / -2 / -2 | 0/0 | 3107/4938 | 1249/0/0 |
| ALNS_THEN_CHANGE_SWAP | ALL | 8 | 6/8 | 2/0/6 | [0]hard/[0/-7680/-7127/0]soft / [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-5283/0]soft | [0,0,0,-1032,0] / [0,0,0,-4,0] / [3,3360,8640,-2,0] | -1032 / -4 / -2 | 1330/1486 | 3177/5391 | 8100/0/0 |
| ALNS_THEN_CHANGE_SWAP | fairness.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft | [0,0,0,-4,0] / [0,0,0,-4,0] / [0,0,0,-4,0] | -4 / -4 / -4 | 1330/1358 | 3177/5096 | 1970/0/0 |
| ALNS_THEN_CHANGE_SWAP | preceptor.json | 2 | 0/2 | 2/0/0 | [0]hard/[0/-7680/-7127/0]soft / [0]hard/[0/-7680/-7127/0]soft / [0]hard/[0/-5760/-6639/0]soft | [3,2880,4800,-1032,0] / [3,2880,4800,-1032,0] / [3,3360,8640,-260,0] | -1032 / -1032 / -260 | 1173/1285 | 3125/5391 | 1998/0/0 |
| ALNS_THEN_CHANGE_SWAP | request.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5283/0]soft | [0,0,0,-2,0] / [0,0,0,-2,0] / [0,0,0,-2,0] | -2 / -2 / -2 | 1455/1486 | 3111/4163 | 2154/0/0 |
| ALNS_THEN_CHANGE_SWAP | sample.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft | [0,0,0,-2,0] / [0,0,0,-2,0] / [0,0,0,-2,0] | -2 / -2 / -2 | 1321/1439 | 3107/4513 | 1978/0/0 |
| ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS | ALL | 8 | 6/8 | 4/2/2 | [0]hard/[0/-7680/-7127/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-5281/0]soft | [0,0,0,-1032,0] / [0,0,0,-2,0] / [3,3360,8640,2,0] | -1032 / -2 / 2 | 1430/1522 | 3177/5475 | 8727/0/0 |
| ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS | fairness.json | 2 | 2/2 | 2/0/0 | [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7626/0]soft | [0,0,0,2,0] / [0,0,0,2,0] / [0,0,0,2,0] | 2 / 2 / 2 | 1354/1384 | 3177/5113 | 2014/0/0 |
| ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS | preceptor.json | 2 | 0/2 | 2/0/0 | [0]hard/[0/-7680/-7127/0]soft / [0]hard/[0/-7680/-7127/0]soft / [0]hard/[0/-5760/-6639/0]soft | [3,2880,4800,-1032,0] / [3,2880,4800,-1032,0] / [3,3360,8640,-260,0] | -1032 / -1032 / -260 | 1436/1504 | 3125/5475 | 2496/0/0 |
| ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS | request.json | 2 | 2/2 | 0/2/0 | [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5281/0]soft | [0,0,0,0,0] / [0,0,0,0,0] / [0,0,0,0,0] | 0 / 0 / 0 | 1482/1522 | 3111/4192 | 2195/0/0 |
| ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS | sample.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft | [0,0,0,-2,0] / [0,0,0,-2,0] / [0,0,0,-2,0] | -2 / -2 / -2 | 1418/1430 | 3107/4595 | 2022/0/0 |

## 최초 차이 목적식·prefix equality

- `ALNS_ONLY`: {"hard":[2,0,0],"soft0":[0,0,0],"soft1":[0,0,0],"soft2":[0,0,6],"soft3":[0,0,0],"tie":[0,0,0]}
- `ALNS_THEN_CHANGE_SWAP`: {"hard":[2,0,0],"soft0":[0,0,0],"soft1":[0,0,0],"soft2":[0,0,6],"soft3":[0,0,0],"tie":[0,0,0]}
- `ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS`: {"hard":[2,0,0],"soft0":[0,0,0],"soft1":[0,0,0],"soft2":[2,0,2],"soft3":[0,0,0],"tie":[0,2,0]}

## 단계·선택 분포

`raw.jsonl`의 candidate record에 ALNS destroy/repair, ordered VND Reassign/Swap, protected fairness selector, 각 stage 입력/출력 score·예약/사용/unused budget·termination을 분리 기록합니다. `summary.json`의 `candidate_distribution`은 이를 전체 집계합니다.

## 해석 경계

이 artifact는 Phase 0 수치 gate를 만들거나 promotion/default 활성화를 선언하지 않습니다. 120초 profile은 60초 Opta cache와 비교할 경우 extra-compute comparison으로만 해석해야 하며 equal-cost 승리라고 표현하지 않습니다.
