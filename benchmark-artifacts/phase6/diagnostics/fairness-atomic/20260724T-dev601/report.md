# fairness atomic witness diagnostic

- dataset: `fairness.json`
- Opta evaluation: `150000`
- POJO evaluation: `100000`
- seed: `[601]`

각 후보는 transaction 적용 후 full-score 검증을 통과한 경우에만 판정하고 rollback했습니다. soft[3]은 witness 판정에 사용하지 않았습니다.

| seed | Opta score | incumbent score | diff edge | candidate | witness | rollback/mismatch | elapsed ms |
|---:|---|---|---:|---:|---|---:|---:|
| 601 | [0]hard/[0/0/-7628/0]soft | [0]hard/[0/0/-7628/0]soft | 270 | 299779 | 발견 | 0/0 | 105573 |

### seed 601

- Opta/POJO 재계산: POJO FullScoreCalculator 및 Opta SolutionManager 일치
- candidate count: Counts[reassign=5400, swap=42615, day3Cycle=6552, shiftTypeWindow3Cycle=26656, shiftTypeWindow4Cycle=218556]
- diff graph edge: 270
- 최소 witness: Witness[neighborhood=REASSIGN, changes=[AssignmentDetail[shiftIndex=184, shiftPlanningId=185, date=2026-05-20, shiftType=D, oldEmployee=96f5997d-b0a6-43eb-aa07-f14d1fe75308, newEmployee=6776b499-62a7-4c5a-acca-3cd8142219b2]], before=[0]hard/[0/0/-7628/0]soft, after=[0]hard/[0/0/-7626/0]soft, delta=[0, 0, 0, 2, 0], dateSpanDays=0]
