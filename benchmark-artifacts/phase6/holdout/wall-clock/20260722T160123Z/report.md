# Phase 6 paired benchmark

- 환경: `macos-local`
- partition: `holdout`
- run label: `phase6-POJO_OPTA_STYLE_CHANGE_SWAP-holdout-wall-clock-20260722T160123Z`
- candidate engine: `POJO_OPTA_STYLE_CHANGE_SWAP`
- operator set: `OPTA_STYLE_CHANGE_SWAP_ONLY`
- quantile: nearest-rank, 보간 없음
- 실행 순서: 입력/seed/repeat/profile마다 교차
- warm-up: 실행, 집계 제외
- fixed-evaluation 단위: OptaPlanner는 score calculation, POJO 후보는 candidate evaluation이므로 엔진 간 throughput으로 직접 비교하지 않음

## 품질·tail 요약

| profile | dataset | pairs | feasible Opta/POJO | W/T/L | Opta score p10/median/p90 | POJO score p10/median/p90 | paired delta p10/median/p90 | elapsed p95 Opta/POJO | eval p10/p50/p90 Opta/POJO | best eval p50/p90 POJO | rollback fail |
|---|---|---:|---:|---:|---|---|---|---:|---|---:|---:|
| wall-clock | ALL | 80 | 80/60 | 4/44/32 | [0]hard/[-960/-5760/-5791/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-5279/0]soft | [-22]hard/[-5280/-13920/-6467/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-5279/0]soft | [-22, -5280, -9120, -378, 0] / [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] | 60000/60000 | 2261591/2360145/2516609 / 345564/372383/410392 | 39515/366009 | 0 |
| wall-clock | fairness.json | 20 | 20/20 | 4/4/12 | [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7626/0]soft / [0]hard/[0/0/-7626/0]soft | [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7628/0]soft / [0]hard/[0/0/-7626/0]soft | [0, 0, 0, -2, 0] / [0, 0, 0, -2, 0] / [0, 0, 0, 2, 0] | 60000/60000 | 2177434/2274152/2294613 / 337797/346289/348556 | 39515/192708 | 0 |
| wall-clock | preceptor.json | 20 | 20/0 | 0/0/20 | [0]hard/[-1920/-4800/-5819/0]soft / [0]hard/[0/-7680/-5985/0]soft / [0]hard/[0/-4800/-6005/0]soft | [-23]hard/[-4800/-16800/-6929/0]soft / [-22]hard/[-5280/-13440/-6073/0]soft / [-21]hard/[-2400/-11520/-6233/0]soft | [-23, -12000, -12000, -944, 0] / [-22, -4800, -8640, -370, 0] / [-21, -1920, -3840, -68, 0] | 60000/60000 | 2311849/2358573/2414195 / 366166/369014/370470 | 365836/367717 | 0 |
| wall-clock | request.json | 20 | 20/20 | 0/20/0 | [0]hard/[0/0/-5279/0]soft / [0]hard/[0/0/-5279/0]soft / [0]hard/[0/0/-5279/0]soft | [0]hard/[0/0/-5279/0]soft / [0]hard/[0/0/-5279/0]soft / [0]hard/[0/0/-5279/0]soft | [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] | 60000/60000 | 2434857/2514298/2539135 / 409272/410178/412004 | 32680/141633 | 0 |
| wall-clock | sample.json | 20 | 20/20 | 0/20/0 | [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft | [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft / [0]hard/[0/0/-5713/0]soft | [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] / [0, 0, 0, 0, 0] | 60001/60000 | 2284282/2363984/2382452 / 379193/379772/381302 | 4051/13966 | 0 |

점수 분위수는 전체 `RosterScore`를 사전식으로 정렬해 실제 vector를 선택합니다. paired delta 배열은 `[hard, soft0, soft1, soft2, soft3]`의 `POJO - OptaPlanner` 좌표별 nearest-rank입니다.

## 탐색 선택 분포·best 기여

| move | evaluated | accepted step | rejected | global best events | final best contribution runs |
|---|---:|---:|---:|---:|---:|
| REASSIGN | 15451379 | 894774 | 14556605 | 1862 | 70 |
| SWAP | 14639398 | 381642 | 14257756 | 511 | 10 |

- history length: `400`
- full verification interval: `1000`
- cancelled candidates: `0`
- full verifications: `1305218`

## 수치 결정 체크리스트

이 보고서는 승인 수치를 자동으로 만들지 않습니다. holdout 결과를 근거로 다음 값을 후속 결정해야 합니다.

- paired loss 허용 개수 또는 최소 non-loss 비율
- 상위 soft level별 p10 허용 열화량
- best 도달 평가 횟수의 최소 개선량
- wall-clock p95 상대/절대 허용 한계
- rollback failure 및 score mismatch 허용 건수
- 모든 운영 입력·seed의 hard score `0` 요구 유지 여부

Execution failures: 0
