# Phase 6 POJO ALNS → Change/Swap → ordered VND 장기 benchmark

- 상태: test-only 후보. production 기본값·점수 의미·Phase 0 gate는 변경하지 않았습니다.
- profile: `wall-clock`, partition/seed: `development-pilot` / `[901, 902]`
- warm-up: 실행 후 집계 제외
- Opta cache scope: `input_sha256/seed/profile/opta_budget`; 각 case의 Opta 결과 1개를 세 후보가 공유합니다.
- 비교: 엄격한 `hard > soft[0] > soft[1] > soft[2] > soft[3]`; W/T/L은 최초 차이 목적식으로 결정합니다.
- fixed 단위: Opta score calculation과 POJO candidate evaluation은 서로 달라 처리량 비교가 아닙니다.

## 품질·안전성

| 후보 | dataset | pairs | feasible Opta/POJO | W/T/L | POJO score p10/median/p90 | paired delta p10/median/p90 | soft2 delta p10/median/p90 | best eval p50/p90 | elapsed p95 Opta/POJO | rollback/mismatch/corruption |
|---|---|---:|---:|---:|---|---|---|---:|---:|---:|
| ALNS_ONLY | ALL | 8 | 6/8 | 2/0/6 | [0]hard/[-1440/-5760/-7005/0]soft / [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-5283/0]soft | [0,0,0,-638,0] / [0,0,0,-4,0] / [3,3360,8640,-2,0] | -638 / -4 / -2 | 0/0 | 3151/5476 | 4884/0/0 |
| ALNS_ONLY | fairness.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft | [0,0,0,-4,0] / [0,0,0,-4,0] / [0,0,0,-4,0] | -4 / -4 / -4 | 0/0 | 3151/5363 | 1217/0/0 |
| ALNS_ONLY | preceptor.json | 2 | 0/2 | 2/0/0 | [0]hard/[-1440/-5760/-7005/0]soft / [0]hard/[-1440/-5760/-7005/0]soft / [0]hard/[0/-4800/-6733/0]soft | [3,1440,7680,-638,0] / [3,1440,7680,-638,0] / [3,3360,8640,-626,0] | -638 / -638 / -626 | 0/0 | 3116/5476 | 739/0/0 |
| ALNS_ONLY | request.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5283/0]soft | [0,0,0,-2,0] / [0,0,0,-2,0] / [0,0,0,-2,0] | -2 / -2 / -2 | 0/0 | 3099/4740 | 1527/0/0 |
| ALNS_ONLY | sample.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft | [0,0,0,-2,0] / [0,0,0,-2,0] / [0,0,0,-2,0] | -2 / -2 / -2 | 0/0 | 3095/5065 | 1401/0/0 |
| ALNS_THEN_CHANGE_SWAP | ALL | 8 | 6/8 | 2/0/6 | [0]hard/[0/-7680/-7149/0]soft / [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-5283/0]soft | [0,0,0,-1054,0] / [0,0,0,-4,0] / [3,3360,8640,-2,0] | -1054 / -4 / -2 | 0/1087 | 3151/4879 | 3806/0/0 |
| ALNS_THEN_CHANGE_SWAP | fairness.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft | [0,0,0,-4,0] / [0,0,0,-4,0] / [0,0,0,-4,0] | -4 / -4 / -4 | 0/920 | 3151/4691 | 1027/0/0 |
| ALNS_THEN_CHANGE_SWAP | preceptor.json | 2 | 0/2 | 2/0/0 | [0]hard/[0/-7680/-7149/0]soft / [0]hard/[0/-7680/-7149/0]soft / [0]hard/[0/-5760/-6657/0]soft | [3,2880,4800,-1054,0] / [3,2880,4800,-1054,0] / [3,3360,8640,-278,0] | -1054 / -1054 / -278 | 0/416 | 3116/4879 | 440/0/0 |
| ALNS_THEN_CHANGE_SWAP | request.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5283/0]soft | [0,0,0,-2,0] / [0,0,0,-2,0] / [0,0,0,-2,0] | -2 / -2 / -2 | 0/1087 | 3099/4167 | 1253/0/0 |
| ALNS_THEN_CHANGE_SWAP | sample.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft | [0,0,0,-2,0] / [0,0,0,-2,0] / [0,0,0,-2,0] | -2 / -2 / -2 | 0/1010 | 3095/4466 | 1086/0/0 |
| ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS | ALL | 8 | 6/8 | 2/0/6 | [0]hard/[0/-7680/-7149/0]soft / [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-5283/0]soft | [0,0,0,-1054,0] / [0,0,0,-4,0] / [3,3360,8640,-2,0] | -1054 / -4 / -2 | 0/1089 | 3151/4906 | 3790/0/0 |
| ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS | fairness.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft / [0]hard/[0/0/-7632/0]soft | [0,0,0,-4,0] / [0,0,0,-4,0] / [0,0,0,-4,0] | -4 / -4 / -4 | 0/920 | 3151/4677 | 1027/0/0 |
| ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS | preceptor.json | 2 | 0/2 | 2/0/0 | [0]hard/[0/-7680/-7149/0]soft / [0]hard/[0/-7680/-7149/0]soft / [0]hard/[0/-5760/-6657/0]soft | [3,2880,4800,-1054,0] / [3,2880,4800,-1054,0] / [3,3360,8640,-278,0] | -1054 / -1054 / -278 | 0/413 | 3116/4906 | 429/0/0 |
| ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS | request.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5283/0]soft | [0,0,0,-2,0] / [0,0,0,-2,0] / [0,0,0,-2,0] | -2 / -2 / -2 | 0/1089 | 3099/4195 | 1255/0/0 |
| ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS | sample.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft / [0]hard/[0/0/-5715/0]soft | [0,0,0,-2,0] / [0,0,0,-2,0] / [0,0,0,-2,0] | -2 / -2 / -2 | 0/1009 | 3095/4466 | 1079/0/0 |

## 최초 차이 목적식·prefix equality

- `ALNS_ONLY`: {"hard":[2,0,0],"soft0":[0,0,0],"soft1":[0,0,0],"soft2":[0,0,6],"soft3":[0,0,0],"tie":[0,0,0]}
- `ALNS_THEN_CHANGE_SWAP`: {"hard":[2,0,0],"soft0":[0,0,0],"soft1":[0,0,0],"soft2":[0,0,6],"soft3":[0,0,0],"tie":[0,0,0]}
- `ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS`: {"hard":[2,0,0],"soft0":[0,0,0],"soft1":[0,0,0],"soft2":[0,0,6],"soft3":[0,0,0],"tie":[0,0,0]}

## 단계·선택 분포

`raw.jsonl`의 candidate record에 ALNS destroy/repair, ordered VND Reassign/Swap, protected fairness selector, 각 stage 입력/출력 score·예약/사용/unused budget·termination을 분리 기록합니다. `summary.json`의 `candidate_distribution`은 이를 전체 집계합니다.

## 해석 경계

이 artifact는 Phase 0 수치 gate를 만들거나 promotion/default 활성화를 선언하지 않습니다. 120초 profile은 60초 Opta cache와 비교할 경우 extra-compute comparison으로만 해석해야 하며 equal-cost 승리라고 표현하지 않습니다.
