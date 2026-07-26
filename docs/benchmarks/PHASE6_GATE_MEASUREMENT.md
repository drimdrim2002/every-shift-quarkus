# Phase 6 promotion gate 측정 가이드

## 목적

이 benchmark는 동일 입력·seed의 OptaPlanner와 선택한 POJO 후보를 paired 비교해
Phase 0에서 누락된 비열화 기준과 tail guardrail을 결정할 원자료를 생성한다. 기본 후보는
baseline-only `POJO_ALNS`이며, OptaPlanner의 기본 Change/Swap + Late Acceptance 구조를
검증하는 `POJO_OPTA_STYLE_CHANGE_SWAP`은 별도 script로 실행한다.

측정 대상은 다음과 같다.

- 운영 입력·seed별 hard feasibility
- 사전식 score의 p10/median/p90
- POJO_ALNS 기준 paired win/tie/loss
- `[hard, soft0, soft1, soft2, soft3]` 좌표별 paired delta p10/median/p90
- best 도달 평가 횟수와 시간
- elapsed p95와 최악 실행시간
- repair/operator 실패, rollback 시도와 rollback 검증 실패
- operator 선택 분포, global-best event, final-best 기여 실행 수
- 결정론적 fixed-evaluation 재실행 결과

warm-up은 실행하지만 artifact 집계에서 제외한다. 입력·seed·repeat·profile별 엔진 실행
순서는 교차한다. wall-clock과 fixed-evaluation은 서로 다른 artifact로 생성한다.

## 1. 빠른 smoke 실행

먼저 harness와 로컬 환경만 확인한다. 이 결과는 승인 근거로 사용하지 않는다.

```bash
./scripts/benchmark/run-phase6-benchmark.sh quick
```

## 2. 정규 wall-clock 측정

기본값은 운영 입력 4종, 별도 holdout seed 10개, 2회 반복, 엔진당 60초다.
총 160회의 solver 실행이므로 warm-up과 초기화 시간을 제외해도 약 160분이 필요하다.

```bash
./scripts/benchmark/run-phase6-benchmark.sh wall-clock
```

OptaPlanner 탐색 구조를 모사한 Change/Swap-only lane은 아래처럼 별도로 실행한다. 이 lane은
production selector나 ALNS 기본 연산자를 바꾸지 않는다.

```bash
./scripts/benchmark/run-phase6-opta-style-move-benchmark.sh wall-clock
```

artifact는 다음 경로 아래 생성된다.

```text
benchmark-artifacts/phase6/holdout/wall-clock/<UTC timestamp>/
├── raw.jsonl
├── summary.json
└── report.md
```

`report.md`와 `summary.json`의 `ALL` 및 dataset별 행에서 다음을 검토한다.

- OptaPlanner/POJO_ALNS 평가 횟수 p10/median/p90
- feasible 실행 수
- paired W/T/L
- score와 paired delta의 lower tail
- elapsed p95/max
- rollback failure, score mismatch, execution failure

## 3. fixed-evaluation 측정

두 엔진의 평가 단위는 같지 않다.

- OptaPlanner: score calculation 수
- POJO_ALNS: complete candidate 평가 수

따라서 wall-clock 결과의 엔진별 실제 평가량을 검토한 뒤 각 예산을 별도로 확정한다.
확정 전에는 fixed benchmark를 실행할 수 없도록 스크립트가 두 환경 변수를 필수로 요구한다.

```bash
OPTAPLANNER_EVALUATION_LIMIT=<확정값> \
POJO_EVALUATION_LIMIT=<확정값> \
./scripts/benchmark/run-phase6-benchmark.sh fixed
```

Change/Swap-only lane도 같은 형식으로 실행한다. `POJO_EVALUATION_LIMIT`에는 해당 lane의
wall-clock report에서 확인한 **candidate evaluation** p10을 넣는다. OptaPlanner의
`score calculation`과 같은 단위가 아니므로, 이 명령은 엔진 간 처리량 비교가 아니라 각
엔진에 wall-clock 하한에서 관측된 예산을 각각 부여하는 재현성·품질 측정이다.

```bash
OPTAPLANNER_EVALUATION_LIMIT=<OptaPlanner wall-clock p10> \
POJO_EVALUATION_LIMIT=<Change/Swap lane wall-clock p10> \
./scripts/benchmark/run-phase6-opta-style-move-benchmark.sh fixed
```

fixed 결과는 다음 별도 경로에 생성된다.

```text
benchmark-artifacts/phase6/holdout/fixed/<UTC timestamp>/
```

## 데이터·seed 분리

정규 승인 측정의 기본 holdout seed는 `101..110`이며 기존 quick baseline과 운영 안정성
테스트 seed에서 분리했다. 승인 데이터는 네 운영 입력 전체다.

후보 연산자 튜닝에서는 이 holdout seed를 사용하지 않는다. 후보별 작은 실패 패턴 데이터와
별도 tuning seed를 다음처럼 명시해 같은 script를 실행한다.

```bash
PARTITION=tuning \
DATASETS=<후보별 작은 데이터 CSV> \
SEEDS=<별도 tuning seed CSV> \
REPEATS=2 \
WALL_CLOCK_SECONDS=10 \
./scripts/benchmark/run-phase6-benchmark.sh wall-clock
```

Change/Swap-only lane의 tuning도 끝에 `run-phase6-opta-style-move-benchmark.sh`를 사용한다.
현재 `fairness_test.json`은 빠른 회귀용 단일 fixture이므로, 그것 하나의 결과로 tuning 또는
승격을 결정하면 안 된다. 후보별로 서로 다른 실패 패턴을 재현하는 fixture를 둘 이상 준비한
뒤 holdout 입력 4종 및 `101..110` seed와 겹치지 않는 dataset/seed 조합을 명시한다.

후보 튜닝이 끝난 뒤에만 기본 holdout 설정으로 승인 benchmark를 실행한다. tuning 결과나
단일 데이터셋의 평균값은 승격 근거로 사용하지 않는다.

## 환경 변수

| 변수 | 기본값 | 의미 |
|---|---|---|
| `PARTITION` | `holdout` | `tuning`, `holdout`, `smoke` 등의 artifact 구분 |
| `DATASETS` | 운영 입력 4종 | 쉼표로 구분한 resource 파일명 |
| `SEEDS` | `101..110` | 쉼표로 구분한 고정 seed |
| `REPEATS` | `2` | seed/profile별 반복 수 |
| `WALL_CLOCK_SECONDS` | `60` | 엔진별 wall-clock 예산 |
| `OPTAPLANNER_EVALUATION_LIMIT` | fixed 모드 필수 | OptaPlanner score calculation 예산 |
| `POJO_EVALUATION_LIMIT` | fixed 모드 필수 | POJO_ALNS complete candidate 예산 |
| `WARMUP` | 정규 실행 `true` | 집계에서 제외되는 warm-up 여부 |
| `ENVIRONMENT` | `macos-local` | `macos-local` 또는 `container` |
| `OUTPUT_DIR` | timestamp 기반 경로 | artifact 출력 경로 override |

## 합의된 Phase 0 gate (2026-07-26)

사용자 합의에 따라 다음 gate를 적용한다. 이 gate는 production 기본값을 자동 변경하지 않으며,
test-only 후보가 다음 검증 단계로 갈 수 있는 최소 증거 기준이다.

| 항목 | 합의 기준 |
|---|---|
| 점수 비교 | 엄격한 사전식 `hard > soft[0] > soft[1] > soft[2] > soft[3]`; 상위 좌표 우위는 모든 하위 좌표보다 중요 |
| 비교 profile | warm-up 제외, crossed order, 동일 입력·seed의 paired **60초 동등 wall-clock** 비교 |
| 최소 품질 | 네 운영 입력 × 분리 validation seed 2개, 총 8쌍에서 **최소 3승·나머지 무승부·0패** |
| hard feasibility | 모든 Opta/POJO 최종해가 feasible이며 hard score `0` |
| 정확성·상태 안전 | rollback failure, score mismatch, state corruption, execution failure 각각 `0` |
| 긴 profile | 120/180초는 saturation 진단용이며 60초 동등비용 gate를 대체하지 않음 |
| 잠긴 holdout | 설정 선택에는 사용하지 않고 후보·gate를 고정한 뒤 사후 평가 1회로만 사용 |

2026-07-26 사용자 최종 판단에 따라, 승리 수보다 **0패(non-loss)** 를 우선한다. 따라서
`ALNS → ordered VND → preceptor-prefix reassign` test-only 후보의 독립 60초 결과
`3승 / 5무 / 0패`은 이 Phase 6 gate를 충족하는 완료 근거로 인정한다. 다만 production
활성화는 별도 사용자 승인과 후속 Phase 7 검증 범위 결정이 필요하다.

## 수치 확정 절차

측정 결과가 gate 수치를 자동 승인하지는 않는다. 다음 순서로 결정한다.

1. 모든 POJO_ALNS 운영 실행의 hard score가 `0`인지 확인한다.
2. fixed-evaluation 재실행에서 assignment·score 재현성을 확인한다.
3. holdout paired loss와 상위 soft level p10 delta의 관측 분포를 검토한다.
4. wall-clock p95/max와 deadline overrun을 검토한다.
5. rollback failure, score mismatch, operator exception의 허용값을 확정한다.
6. 위 근거와 운영 위험 허용도를 함께 사용해 비열화·tail 수치를 문서화한다.

결정 후에는 해당 수치를 Phase 0 benchmark 문서에 기록하고, 후보 연산자 benchmark가 같은
gate를 기계적으로 판정하도록 후속 구현한다.
