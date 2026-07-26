# Phase 6 SA/ALNS 하이퍼파라미터 레이싱 결과

## 결론

테스트 전용 successive racing에서 기존 POJO ALNS보다 나은 조합은 찾았지만,
OptaPlanner보다 좋은 후보는 찾지 못했다. 따라서 production 기본값은 변경하지 않고,
두 검증 리더 모두 `보류`로 판정한다. 이 측정은 Phase 0의 promotion gate를 대신하지 않는다.

- fixed-evaluation 최선 후보: OptaPlanner 대비 `W/T/L = 1/2/5`, feasible `8/8`
- 10초 wall-clock 동일 후보: `W/T/L = 1/1/6`, feasible `8/8`
- `fairness.json`: 두 profile 모두 두 seed 전패
- rollback failure, score mismatch, state corruption: 모두 `0`
- ALNS best 도달 evaluation 중앙값: `0`

즉, SA 온도나 ALNS 적응 가중치만 조정해서는 알려진 fairness 열세를 안정적으로
해결하지 못했다. 일부 case에서 실제 global-best 개선은 발생했으나, 적어도 절반의
case에서는 warm start를 넘는 최종 best가 한 번도 없었다.

## 실험 격리와 선택 규칙

- 훈련: `201,202` × `fairness.json,preceptor.json,request.json,sample.json`
- 검증: `301,302` × 같은 4개 데이터셋
- 잠긴 기존 holdout `101..110`: 튜닝과 검증에 사용하지 않음
- 훈련 예산: ALNS complete-candidate evaluation `1,000`
- 검증 예산: ALNS complete-candidate evaluation `5,000`
- fixed 비교:
  - ALNS complete-candidate evaluation `5,000`
  - OptaPlanner score calculation `2,296,836`
- wall-clock 비교: 엔진별 `10초`
- warm-up: 실행하되 집계에서 제외
- 실행 순서: case index별 Opta-first/ALNS-first 교차
- Opta 결과: 같은 case의 여러 ALNS 후보 비교에서 1회만 실행하고 메모리 캐시
- 설정 선택: 동일 case의 사전식 feasible 수 → pairwise 승 → 패 → ordinal rank sum
- 품질 동률: wall-clock 노이즈를 사용하지 않고 production 기본값과 변경 수가 적은 설정 우선

OptaPlanner의 score calculation과 ALNS complete-candidate evaluation은 다른 단위다.
fixed-evaluation 표는 품질 비교이며 엔진 간 처리량 비교가 아니다.

전체 훈련 partition `201..220`과 검증 partition `301..310`을 모두 사용하지 않은 이유는
승인된 약 2시간 로컬 예산 안에서 단계별 조합, 분리 검증, Opta fixed 및 wall-clock 비교를
모두 끝내기 위해서다. 남은 seed는 후속 blind 확장에 사용할 수 있도록 보존했다.

## 탐색한 조합

| 영역 | 값 |
|---|---|
| SA 초기 악화 수락확률 | `0.05, 0.10, 0.20, 0.35` |
| SA 최종 온도비 | `0.001, 0.005, 0.01, 0.05` |
| calibration attempt | `32, 64, 128` |
| destroy rate | `0.02, 0.05, 0.08, 0.12` |
| qMax | `4, 8, 12` |
| repair retry | `1, 2, 3` |
| adaptive reaction | `0.05, 0.10, 0.20, 0.40` |
| adaptive segment | `50, 100, 250` |
| fairness hotspot pair | `OFF, ON` |

`qMin=1`, absolute removal limit `12`, SA fallback cooling evaluation `10,000`,
operator reward `10/5/1/0`은 이번 레이싱에서 고정했다.

## 단계별 결과

| 단계 | 후보 | case | eval | 훈련 리더 | pairwise W/T/L | global best |
|---|---:|---:|---:|---|---:|---:|
| SA | 16 | 8 | 1,000 | `p0.20 / final 0.005` | 15/105/0 | 1 |
| calibration | 6 | 8 | 1,000 | `64` 유지 | 5/35/0 | 1 |
| destroy | 24 | 8 | 1,000 | `rate 0.02 / qMax 8` | 40/125/19 | 2 |
| repair | 7 | 8 | 1,000 | retry `2` 유지 | 2/46/0 | 2 |
| adaptive | 25 | 8 | 1,000 | `reaction 0.05 / segment 250` | 46/144/2 | 4 |
| operator set | 7 | 8 | 1,000 | fairness `OFF` | 15/30/3 | 4 |
| 분리 검증 | 6 | 8 | 5,000 | 아래 후보 A | 11/25/4 | 6 |

훈련에서는 fairness pair OFF가 1위였지만, 더 큰 검증 예산에서는 ON 설정 두 개가
상위에 올랐다. 이는 해당 pair의 효과가 seed와 예산에 민감하고, 현재 8-case
검증만으로 안정성을 선언할 수 없음을 뜻한다.

## 검증 리더

### 후보 A

- SA 초기 수락확률: `0.20`
- 최종 온도비: `0.01`
- calibration: `64`
- destroy rate / qMax: `0.02 / 8`
- repair retry: `2`
- reaction / segment: `0.20 / 250`
- fairness hotspot removal + fairness-aware regret-2 repair: `ON`

### 후보 B

후보 A와 같고 최종 온도비만 `0.005`다.

초기 수락확률, calibration, qMax, repair retry, reaction은 결국 기존 기본값을
유지했다. 반복적으로 남은 변화는 destroy rate `0.05 → 0.02`, segment
`100 → 250`, fairness pair ON이었다. 최종 온도비 `0.01`과 `0.005` 사이에는
profile별 우열이 뒤집혀 확정 근거가 없다.

## OptaPlanner 비교

### 전체

| profile | 후보 | feasible Opta/ALNS | W/T/L | Opta score p10/median/p90 | ALNS score p10/median/p90 | paired delta p10/median/p90 | best eval p50/p90 Opta | best eval p50/p90 ALNS | p95 ms Opta/ALNS | rollback |
|---|---|---:|---:|---|---|---|---|---|---:|---:|
| fixed | A | 8/8 | 1/2/5 | `[0;-2880/-7680/-5763/0]` / `[0;0/0/-7626/0]` / `[0;0/0/-5279/0]` | `[0;0/-5280/-7013/0]` / `[0;0/0/-7632/0]` / `[0;0/0/-5279/0]` | `[0,0,-2400,-1288,0]` / `[0,0,0,-4,0]` / `[0,2880,2400,0,0]` | 58,875 / 2,110,428 | 0 / 4,868 | 60,268 / 8,174 | 15,524 / 0 |
| fixed | B | 8/8 | 1/1/6 | 동일 | `[0;0/-5280/-7013/0]` / `[0;0/0/-7630/0]` / `[0;0/0/-5279/0]` | 동일 | 58,875 / 2,110,428 | 0 / 4,236 | 60,268 / 8,276 | 14,780 / 0 |
| 10초 | A | 8/8 | 1/1/6 | `[0;-3360/-9600/-5879/0]` / `[0;0/0/-7626/0]` / `[0;0/0/-5279/0]` | `[0;0/-5280/-6873/0]` / `[0;0/0/-7632/0]` / `[0;0/0/-5279/0]` | `[0,0,0,-1282,0]` / `[0,0,0,-4,0]` / `[0,3360,4320,0,0]` | 58,875 / 355,352 | 0 / 8,062 | 10,002 / 9,991 | 21,561 / 0 |
| 10초 | B | 8/8 | 1/2/5 | 동일 | 동일 | 동일 | 58,875 / 355,352 | 0 / 8,062 | 10,002 / 9,991 | 20,201 / 0 |

점수 표기는 `[hard;soft0/soft1/soft2/soft3]`이다. p10/median/p90은 서로 다른
데이터셋을 합산하지 않고 사전식 점수 순서의 nearest-rank로 계산했다.

### 데이터셋별 W/T/L

| profile | 후보 | fairness | preceptor | request | sample |
|---|---|---:|---:|---:|---:|
| fixed | A | 0/0/2 | 1/0/1 | 0/2/0 | 0/0/2 |
| fixed | B | 0/0/2 | 1/0/1 | 0/1/1 | 0/0/2 |
| 10초 | A | 0/0/2 | 1/0/1 | 0/1/1 | 0/0/2 |
| 10초 | B | 0/0/2 | 1/0/1 | 0/2/0 | 0/0/2 |

유일한 승리는 `preceptor.json` seed 301이다. ALNS가 OptaPlanner보다 상위 soft
레벨을 개선했기 때문에 사전식 승리다. 반면 목표였던 `fairness.json`에서는 후보 A/B
모두 두 seed에서 warm start `-7632` 부근을 벗어나지 못했고 OptaPlanner의
`-7626/-7628`에 패했다.

## operator 선택과 unique final-best 기여

| profile | 후보 | fairness pair 선택 | global best | final best | random final best | related final best |
|---|---|---:|---:|---:|---:|---:|
| fixed | A | 12,545 | 3 | 2 | 1 | 0 |
| fixed | B | 12,807 | 1 | 0 | 1 | 2 |
| 10초 | A | 16,614 | 1 | 0 | 3 | 1 |
| 10초 | B | 17,309 | 0 | 0 | 2 | 2 |

fairness pair는 후보 A fixed에서 8개 case 중 2개의 마지막 global-best를 만들었지만,
10초 profile에서는 많은 선택과 current improvement에도 final-best 기여가 0이었다.
후보 B는 두 profile 모두 final-best 기여가 0이다. 따라서 선택 빈도나 current-state
개선만으로 이 pair를 기본 활성화할 근거가 없다.

## 활성/보류

| 대상 | 판정 | 이유 |
|---|---|---|
| SA 초기 수락확률 `0.20` | 유지 후보 | 변경값의 반복 가능한 우위 없음 |
| calibration `64` | 유지 후보 | 32/128의 검증 우위 없음 |
| destroy rate `0.02` | 테스트 후보 유지 | 훈련·검증 상위 조합에 반복 등장 |
| segment `250` | 테스트 후보 유지 | 검증 상위 조합에 반복 등장 |
| final temperature `0.005` | 보류 | fixed/wall profile에서 A/B 우열 역전 |
| fairness pair ON | 보류 | 검증 순위는 개선했으나 fairness fixture 전패, final-best 기여 불안정 |
| production 기본 활성화 | 하지 않음 | OptaPlanner 대비 전체 패배 우세, 사용자 gate 미정 |

## 재현 artifact와 명령

- raw: `benchmark-artifacts/phase6/tuning/tune/20260724T025226Z/raw.jsonl`
- summary: `benchmark-artifacts/phase6/tuning/tune/20260724T025226Z/summary.json`
- report: `benchmark-artifacts/phase6/tuning/tune/20260724T025226Z/report.md`
- Opta fixed cache: `benchmark-artifacts/phase6/tuning/tune/20260724T025226Z/opta-cache-fixed-evaluations.json`
- Opta wall cache: `benchmark-artifacts/phase6/tuning/tune/20260724T025226Z/opta-cache-wall-clock.json`

실제 실행 명령:

```bash
cd /Users/brown/workspace/every-shift-quarkus
./scripts/benchmark/run-phase6-alns-tuning.sh tune
```

남은 `201..220`, `301..310` seed로 확장하려면 기존 artifact를 덮어쓰지 않는 새
`OUTPUT_DIR`를 지정한다. 다만 현재 결과가 OptaPlanner보다 나쁘므로, 하이퍼파라미터
grid만 확대하는 장시간 실행의 우선순위는 낮다.

## 다음 결정과 Phase 7

아직 필요한 사용자 결정은 Phase 0의 수치 gate다. 최소한 다음을 합의해야 한다.

1. 사전식 W/T/L에서 허용할 loss 수와 dataset별 veto 여부
2. feasible 비율의 최소값
3. wall-clock p95와 rollback 허용치
4. fairness/preceptor 상위 목적식과 하위 fairness 좌표 사이의 우선순위

현재 후보는 `fairness.json`을 해결하지 못했으므로 promotion을 위한 Phase 7로
넘길 근거는 없다. Phase 7을 탐색 연구로 진행하는 것은 가능하지만, 다음 실험은
SA/ALNS 숫자 grid 확대보다 warm start를 깨는 move-level hybrid, fairness 개선을
직접 검증하는 restricted local search, 또는 Opta-style Change/Swap과 ALNS의
순차 결합을 우선해야 한다.
