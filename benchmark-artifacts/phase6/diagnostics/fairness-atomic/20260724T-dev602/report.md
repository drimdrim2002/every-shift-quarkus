# fairness atomic witness diagnostic

- dataset: `fairness.json`
- Opta evaluation: `150000`
- POJO evaluation: `100000`
- seed: `[602]`

각 후보는 transaction 적용 후 full-score 검증을 통과한 경우에만 판정하고 rollback했습니다. soft[3]은 witness 판정에 사용하지 않았습니다.

| seed | Opta score | incumbent score | diff edge | candidate | witness | rollback/mismatch | elapsed ms |
|---:|---|---|---:|---:|---|---:|---:|
| 602 | [0]hard/[0/0/-7628/0]soft | [0]hard/[0/0/-7628/0]soft | 214 | 299615 | 발견 | 0/0 | 108886 |

### seed 602

- Opta/POJO 재계산: POJO FullScoreCalculator 및 Opta SolutionManager 일치
- candidate count: Counts[reassign=5400, swap=42613, day3Cycle=6552, shiftTypeWindow3Cycle=26662, shiftTypeWindow4Cycle=218388]
- diff graph edge: 214
- 최소 witness: Witness[neighborhood=REASSIGN, changes=[AssignmentDetail[shiftIndex=21, shiftPlanningId=22, date=2026-05-03, shiftType=D, oldEmployee=581e315f-aaa6-4cec-9414-250150b3d1fa, newEmployee=7ad4f4e2-bbfb-448a-b9a0-7205ce683673]], before=[0]hard/[0/0/-7628/0]soft, after=[0]hard/[0/0/-7626/0]soft, delta=[0, 0, 0, 2, 0], dateSpanDays=0]
