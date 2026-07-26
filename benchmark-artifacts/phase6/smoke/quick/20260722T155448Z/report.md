# Phase 6 paired benchmark

- 환경: `macos-local`
- partition: `smoke`
- run label: `phase6-POJO_ALNS_BASELINE-smoke-quick-20260722T155448Z`
- candidate engine: `POJO_ALNS_BASELINE`
- operator set: `BASELINE_ONLY`
- quantile: nearest-rank, 보간 없음
- 실행 순서: 입력/seed/repeat/profile마다 교차
- warm-up: 미실행
- fixed-evaluation 단위: OptaPlanner는 score calculation, POJO 후보는 candidate evaluation이므로 엔진 간 throughput으로 직접 비교하지 않음

## 품질·tail 요약

| profile | dataset | pairs | feasible Opta/POJO | W/T/L | Opta score p10/median/p90 | POJO score p10/median/p90 | paired delta p10/median/p90 | elapsed p95 Opta/POJO | eval p10/p50/p90 Opta/POJO | best eval p50/p90 POJO | rollback fail |
|---|---|---:|---:|---:|---|---|---|---:|---|---:|---:|
| wall-clock | ALL | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5279/0]soft | [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5283/0]soft | [0, 0, 0, -4, 0] / [0, 0, 0, -4, 0] / [0, 0, 0, -2, 0] | 3006/2996 | 101123/101123/117897 / 279/279/659 | 0/0 | 0 |
| wall-clock | request.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5279/0]soft | [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5283/0]soft | [0, 0, 0, -4, 0] / [0, 0, 0, -4, 0] / [0, 0, 0, -2, 0] | 3006/2996 | 101123/101123/117897 / 279/279/659 | 0/0 | 0 |
| fixed-evaluations | ALL | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5281/0]soft | [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5283/0]soft | [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] | 451/1866 | 20000/20000/20000 / 10/10/10 | 0/0 | 0 |
| fixed-evaluations | request.json | 2 | 2/2 | 0/0/2 | [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5281/0]soft / [0]hard/[0/0/-5281/0]soft | [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5283/0]soft / [0]hard/[0/0/-5283/0]soft | [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] | 451/1866 | 20000/20000/20000 / 10/10/10 | 0/0 | 0 |

점수 분위수는 전체 `RosterScore`를 사전식으로 정렬해 실제 vector를 선택합니다. paired delta 배열은 `[hard, soft0, soft1, soft2, soft3]`의 `POJO - OptaPlanner` 좌표별 nearest-rank입니다.

## 탐색 선택 분포·best 기여

### Destroy

| operator | selections | global best events | current improvements | accepted worsening | rejections | final best contribution runs |
|---|---:|---:|---:|---:|---:|---:|
| PRECEPTOR_RELATION_GROUP_REMOVAL | 291 | 0 | 19 | 27 | 245 | 0 |
| RANDOM_REMOVAL | 301 | 0 | 25 | 24 | 252 | 0 |
| RELATED_SHIFT_REMOVAL | 366 | 0 | 15 | 273 | 78 | 0 |

### Repair

| operator | selections | global best events | current improvements | accepted worsening | rejections | final best contribution runs |
|---|---:|---:|---:|---:|---:|---:|
| GREEDY_REPAIR | 232 | 0 | 13 | 105 | 114 | 0 |
| REGRET_2_REPAIR | 241 | 0 | 20 | 92 | 129 | 0 |
| RELATION_AWARE_REPAIR | 485 | 0 | 26 | 127 | 332 | 0 |

`final best contribution runs`는 해당 run의 마지막 global-best 개선을 만든 operator가 이 operator였던 실행 수입니다.

## 수치 결정 체크리스트

이 보고서는 승인 수치를 자동으로 만들지 않습니다. holdout 결과를 근거로 다음 값을 후속 결정해야 합니다.

- paired loss 허용 개수 또는 최소 non-loss 비율
- 상위 soft level별 p10 허용 열화량
- best 도달 평가 횟수의 최소 개선량
- wall-clock p95 상대/절대 허용 한계
- rollback failure 및 score mismatch 허용 건수
- 모든 운영 입력·seed의 hard score `0` 요구 유지 여부

Execution failures: 0
