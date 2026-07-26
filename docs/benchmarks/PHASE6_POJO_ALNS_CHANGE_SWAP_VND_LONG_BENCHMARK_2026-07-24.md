# Phase 6: POJO ALNS → Change/Swap → ordered VND 장기 benchmark

## 결론

`POJO ALNS → ordered Change/Swap VND → protected fairness reassign`는 **test-only 보류 후보**다. 60초 동등 wall-clock 검증에서 OptaPlanner frozen 기준 대비 8쌍 모두 feasible, 사전식 4승 4무 0패를 기록했지만, 분리 검증 seed가 2개뿐이고 Phase 0 승격 수치 gate도 아직 합의되지 않았다. 따라서 production 기본값, 점수 의미, 배포 설정은 변경하지 않았다.

120초 동등비용 검증은 같은 분리 seed와 입력으로 별도 artifact에 기록한다. 60초와 120초를 섞어 승패를 주장하지 않으며, 120초 결과가 60초보다 계속 개선될 때만 180초를 추가하는 조건으로 해석한다.

## 범위와 불변 조건

- 후보는 production CDI/기본 solver 선택에 등록하지 않은 benchmark/test 전용 클래스다.
- 비교 순서는 가중합이 아닌 엄격한 `hard > soft[0] > soft[1] > soft[2] > soft[3]`다. W/T/L은 최초로 다른 좌표만으로 판정한다. 예를 들어 soft[0] 승리와 soft[1]/soft[2] 손실이 같이 있어도 사전식 승리다.
- 각 stage handoff와 최종 best는 `FullScoreCalculator`로 재계산한다. warm start의 complete/pinned 불변성, transaction rollback fingerprint, score mismatch/state corruption을 확인한다. mismatch는 `SCORE_MISMATCH`로 끝내고 마지막 verified best만 사용한다.
- 기존 POJO `FullScoreCalculator`와 Opta `SolutionManager`의 1 hard/4 soft 및 constraint breakdown differential test를 재실행했다.
- 기존 lock된 holdout seed `101..110`, tuning seed `201/202`, `301/302`, `401/402`, `501/502`, `601/602`, `701/702`, `801/802`는 설정 선택에 사용하지 않았다. pilot은 `901/902`, 분리 검증은 `1001/1002`다.

## 감사 결과와 구현

기존 `AlnsSolverEngine`, `MoveTransaction`, `SearchState`, `FullScoreCalculator`, `FairnessRestrictedLocalSearchEngine`의 hotspot-guided protected reassign selector를 재사용했다. 이전 sequential hybrid는 이 후보의 ordered VND·단계 계약과 다르므로 중복 사용하지 않았다.

새 test-only 구성은 다음과 같다.

1. ALNS가 diversification을 수행한다.
2. `OrderedVndLocalSearchEngine`이 안정 index와 명시적 seed로 후보를 회전시킨 뒤 Reassign, Swap 순으로 각각 최대 256개를 탐색한다.
3. 어떤 neighborhood에서 엄격한 사전식 개선을 commit하면 Reassign부터 재시작한다. 두 neighborhood pass가 모두 개선을 만들지 못하면 수렴한다.
4. 마지막 protected fairness 단계는 hard/soft[0]/soft[1] prefix가 같은 후보만 허용하고 soft[2] 개선만 수락한다.
5. 각 후보는 transaction 내부에서 전체 점수를 재계산해 검증한다. VND/fairness의 full verification 비율은 후보 평가에 대해 100%이며, stage/최종 검증이 추가된다.

동률 해소와 순서는 `RosterScore.compareTo`의 사전식 비교, stable shift index, `seed + neighborhood + evaluation` 혼합값으로 고정한다. 상위 좌표 승리를 하위 좌표 손실 때문에 거절하지 않는다.

고정 평가 예산은 ALNS 80%, Change/Swap 20% 또는 protected mode에서 ALNS 80%/VND 15%/fairness 5%로 예약한다. 앞 stage의 고정 평가 예산 잔여분은 다음 stage로 넘기되 fairness 5% 예약분은 VND가 침범하지 않는다. wall-clock은 같은 80%/15%/5% deadline을 사용한다. VND/fairness가 조기 수렴하면 해당 wall 잔여시간은 탐색하지 못한 전역 unused time으로 artifact에 기록한다.

초기 pilot에서 ALNS의 stage deadline을 전체 종료로 잘못 전파해 VND가 실행되지 않는 결함을 발견했다. 해당 `20260724T174500Z` artifact는 품질 판단에서 폐기했고, 첫 stage의 `DEADLINE_REACHED` 뒤에도 다음 stage를 실행하도록 고친 뒤 regression test를 추가했다. 아래 결과는 수정 뒤 artifact만 사용한다.

## 코드와 테스트

- `src/main/java/org/acme/solver/lahc/OrderedVndLocalSearchEngine.java`
- `src/main/java/org/acme/solver/lahc/OrderedVndLocalSearchMetrics.java`
- `src/main/java/org/acme/solver/lahc/AlnsChangeSwapVndHybridSolverEngine.java`
- `src/main/java/org/acme/solver/lahc/AlnsChangeSwapVndHybridMetrics.java`
- `src/test/java/org/acme/solver/lahc/OrderedVndLocalSearchEngineTest.java`
- `src/test/java/org/acme/solver/lahc/AlnsChangeSwapVndHybridSolverEngineTest.java`
- `src/test/java/org/acme/solver/benchmark/Phase6HybridVndLongBenchmarkTest.java`
- `scripts/benchmark/run-phase6-hybrid-vnd-long-benchmark.sh`

작은 fixture는 (a) Reassign 개선 뒤 처음 neighborhood로 재시작, (b) 비개선 transaction rollback 및 warm start 보존, (c) 동일 seed 결정론성, (d) 사전식 prefix 우선, (e) ALNS deadline 뒤 VND stage 실행과 verified warm-start 전달을 고정한다.

실행한 정확성 테스트:

```bash
cd /Users/brown/.codex/worktrees/c574/every-shift-quarkus
./mvnw -q -Dtest=FullScoreCalculatorDifferentialTest,OptaPlannerEngineCompatibilityTest test
./mvnw -q -Dtest=OrderedVndLocalSearchEngineTest,AlnsChangeSwapVndHybridSolverEngineTest test
```

두 명령은 통과했다. 이어 전체 `./mvnw test`도 실행해 **344 tests, failures 0, errors 0, skipped 8**로 통과했다. Quarkus 테스트 HTTP bind도 임시 포트에서 성공했으며 sandbox bind 실패는 없었다.

## Pilot/고정 평가 관측

수정 뒤 pilot artifact는 `/Users/brown/.codex/worktrees/c574/every-shift-quarkus/benchmark-artifacts/phase6/hybrid-vnd-long/pilot/20260724T175300Z`다. 3초 pilot은 안전성·ablation 선택용이며 Opta가 preceptor에서 infeasible인 경우가 있어 품질 우위 근거로 쓰지 않았다. protected 후보는 fairness의 soft[2]를 `-7632 → -7626`으로 개선하고, request에서도 `-5283 → -5281`을 만들었다. rollback/mismatch/corruption은 모두 0이었다.

| 3초 pilot 후보 | Opta/POJO feasible | W/T/L | POJO median | median delta | 해석 |
|---|---:|---:|---|---|---|
| ALNS only | 6/8 | 2/0/6 | `[0]hard/[0/0/-7632/0]soft` | `[0,0,0,-4,0]` | Opta의 preceptor infeasible 2건 때문에 비교 불가 |
| ALNS → Change/Swap | 6/8 | 2/0/6 | `[0]hard/[0/0/-7632/0]soft` | `[0,0,0,-4,0]` | 같은 비교 불가 조건 |
| ALNS → VND → protected fairness | 6/8 | 4/2/2 | `[0]hard/[0/0/-7626/0]soft` | `[0,0,0,-2,0]` | fairness witness 재현·안전성 확인용으로 선택 |

따라서 pilot의 W/T/L은 후보 선택 근거가 아니라 safety/veto 및 구조 선택 근거일 뿐이다.

고정 평가 분리 검증 artifact는 `/Users/brown/.codex/worktrees/c574/every-shift-quarkus/benchmark-artifacts/phase6/hybrid-vnd-long/validation/fixed-20260724T175600Z`다. Opta 50,000 score calculation과 POJO 5,000 complete candidate evaluation은 단위가 달라 처리량 비교가 아니다. preceptor의 Opta 2/2가 infeasible라 그 2승도 품질 증거에서 제외한다. 나머지 6쌍은 soft[2] 2승/2패/2무로 일관된 우위가 아니다.

## 60초 동등 wall-clock 검증

artifact:

- raw: `/Users/brown/.codex/worktrees/c574/every-shift-quarkus/benchmark-artifacts/phase6/hybrid-vnd-long/validation/wall-60s-20260724T175800Z/raw.jsonl`
- summary: `/Users/brown/.codex/worktrees/c574/every-shift-quarkus/benchmark-artifacts/phase6/hybrid-vnd-long/validation/wall-60s-20260724T175800Z/summary.json`
- report: `/Users/brown/.codex/worktrees/c574/every-shift-quarkus/benchmark-artifacts/phase6/hybrid-vnd-long/validation/wall-60s-20260724T175800Z/report.md`

동일 입력·seed에서 Opta와 후보를 교차 순서로 실행했다. warm-up은 실행했으나 집계에서 제외했다. Opta cache scope는 `input_sha256/seed/profile/opta_budget`이고, 이 실행은 후보가 하나라 case당 Opta를 한 번만 수행했다.

| 입력 | 쌍 | feasible Opta/POJO | W/T/L | 최초 차이 | 후보 median score | paired delta median | soft[2] delta p10/median/p90 | best eval p50/p90 | POJO elapsed p95 |
|---|---:|---:|---:|---|---|---|---:|---:|---:|
| fairness | 2 | 2/2 | 2/0/0 | soft[2] 2승 | `[0]hard/[0/0/-7626/0]soft` | `[0,0,0,2,0]` | 2 / 2 / 2 | 17,499 / 17,560 | 50,876 ms |
| preceptor | 2 | 2/2 | 2/0/0 | soft[0] 2승 | `[0]hard/[0/-8160/-7405/0]soft` | `[0,960,-1920,-1540,0]` | -1,540 / -1,540 / -918 | 41,413 / 42,562 | 51,191 ms |
| request | 2 | 2/2 | 0/2/0 | tie 2 | `[0]hard/[0/0/-5279/0]soft` | `[0,0,0,0,0]` | 0 / 0 / 0 | 6,677 / 8,608 | 49,929 ms |
| sample | 2 | 2/2 | 0/2/0 | tie 2 | `[0]hard/[0/0/-5713/0]soft` | `[0,0,0,0,0]` | 0 / 0 / 0 | 14,976 / 17,076 | 50,199 ms |
| 전체 | 8 | 8/8 | **4/4/0** | soft[0] 2승, soft[2] 2승, tie 4 | p10/median/p90 = `[0]hard/[0/-8160/-7405/0]soft` / `[0]hard/[0/0/-7626/0]soft` / `[0]hard/[0/0/-5279/0]soft` | p10/median/p90 = `[0,0,-1920,-1540,0]` / `[0,0,0,0,0]` / `[0,960,0,2,0]` | -1,540 / 0 / 2 | 17,076 / 42,562 | 51,191 ms |

전체 최초-차이 분포는 hard `0/0/0`, soft[0] `2/0/0`, soft[1] `0/0/0`, soft[2] `2/0/0`, soft[3] `0/0/0`, tie `0/4/0`(각각 W/T/L)이다. 따라서 hard prefix equality는 8/8, hard+soft[0] prefix equality는 6/8, hard+soft[0]+soft[1] prefix equality도 6/8, hard+soft[0]+soft[1]+soft[2] equality는 4/8이다.

안전성은 rollback attempt 72,006, score mismatch 0, state corruption 0이다. elapsed p95는 Opta 60,161 ms, POJO 51,191 ms다. 집계 POJO 후보 처리량은 204,551 / 404,186 ms = **506.1 candidate/s**이며 Opta score calculation과 직접 비교하지 않는다. POJO가 deadline 전에 끝난 이유는 ALNS 48초 경계 뒤 VND/fairness가 조기 수렴했기 때문이다.

선택 분포는 ALNS destroy 198,590회, VND Reassign 3,355회/Swap 2,048회, VND accept 17회, protected fairness accept 5회다. VND 5,403개 및 fairness 558개 후보는 모두 full-score로 검증했고 stage·최종 검증까지 각각 5,411/566회였다. 최종 best의 고유 기여 stage는 ALNS 4건, VND 2건, fairness 2건이다. ALNS는 모든 쌍에서 stage deadline, VND와 fairness는 모든 쌍에서 `CONVERGED`로 종료했다.

## 120초 동등 wall-clock 검증과 saturation

완료 artifact:

- raw: `/Users/brown/.codex/worktrees/c574/every-shift-quarkus/benchmark-artifacts/phase6/hybrid-vnd-long/validation/wall-120s-20260724T181500Z/raw.jsonl`
- summary: `/Users/brown/.codex/worktrees/c574/every-shift-quarkus/benchmark-artifacts/phase6/hybrid-vnd-long/validation/wall-120s-20260724T181500Z/summary.json`
- report: `/Users/brown/.codex/worktrees/c574/every-shift-quarkus/benchmark-artifacts/phase6/hybrid-vnd-long/validation/wall-120s-20260724T181500Z/report.md`

이 profile은 Opta와 POJO 모두 120초 제한으로 실행하므로 60초 Opta와의 extra-compute 비교가 아니라 별도의 동등비용 검증이다. 전체 8쌍은 feasible 8/8, W/T/L **4/4/0**이다. 최초 차이는 soft[0] 2승, soft[2] 2승, tie 4건이고 hard prefix equality는 8/8, hard+soft[0] 및 hard+soft[0]+soft[1] equality는 6/8, soft[2]까지 equality는 4/8이다.

후보 score p10/median/p90은 `[0]hard/[0/-8160/-6979/0]soft` / `[0]hard/[0/0/-7622/0]soft` / `[0]hard/[0/0/-5279/0]soft`이고 paired vector delta p10/median/p90은 `[0,0,-1920,-1116,0]` / `[0,0,0,0,0]` / `[0,960,0,4,0]`이다. soft[2] delta p10/median/p90은 `-1116 / 0 / 4`, best evaluation p50/p90은 `17,076 / 90,199`, elapsed p95는 Opta `120,152 ms`, POJO `99,139 ms`다. 집계 POJO 후보 처리량은 417,209 / 787,071 ms = **530.1 candidate/s**다. rollback 140,263회, mismatch 0, state corruption 0이다.

60초 대비 fairness 두 쌍은 모두 `-7626 → -7622`, preceptor 두 번째 쌍은 `-7405 → -6979`으로 개선됐다. 각 120초 run의 ALNS는 96초 deadline까지 도달했고, VND/fairness는 수렴했다. 이후 global unused wall time은 대체로 23.5초다(`raw.jsonl`의 stage `unused_wall_ms`). 최종 best 고유 기여는 ALNS 4, VND 1, protected fairness 3건이다. VND는 5,539개, fairness는 749개 후보를 전체 점수로 검증했다(각각 stage·최종 검증 포함 5,547/757회). 실제 최종 best가 60초보다 계속 개선됐으므로 환경이 허용하는 범위에서 180초 extended profile을 추가했다.

120초/180초를 60초 Opta 결과와 직접 비교하면 extra-compute comparison일 뿐이다. 여기서는 각 profile의 동일 wall-budget Opta와만 W/T/L을 계산한다.

## 180초 extended wall-clock 검증

120초 종료 시점까지의 실제 best 개선을 근거로 실행한 별도 동등비용 profile이다. 이 profile은 60초 대비 extra compute이며, 60초 score와 혼합해 equal-cost 승리라고 표현하지 않는다.

- raw: `/Users/brown/.codex/worktrees/c574/every-shift-quarkus/benchmark-artifacts/phase6/hybrid-vnd-long/validation/wall-180s-20260724T184300Z/raw.jsonl`
- summary: `/Users/brown/.codex/worktrees/c574/every-shift-quarkus/benchmark-artifacts/phase6/hybrid-vnd-long/validation/wall-180s-20260724T184300Z/summary.json`
- report: `/Users/brown/.codex/worktrees/c574/every-shift-quarkus/benchmark-artifacts/phase6/hybrid-vnd-long/validation/wall-180s-20260724T184300Z/report.md`

전체 8쌍은 feasible 8/8, W/T/L **4/4/0**이다. 최초 차이 분포도 soft[0] 2승, soft[2] 2승, tie 4건으로 60/120초와 같다. hard prefix equality는 8/8, hard+soft[0] 및 hard+soft[0]+soft[1] equality는 6/8, soft[2]까지 equality는 4/8이다.

후보 score p10/median/p90은 `[0]hard/[-480/-7680/-7031/0]soft` / `[0]hard/[0/0/-7622/0]soft` / `[0]hard/[0/0/-5279/0]soft`이고 paired delta p10/median/p90은 `[0,0,-1920,-1168,0]` / `[0,0,0,0,0]` / `[0,960,0,4,0]`이다. soft[2] delta p10/median/p90은 `-1168 / 0 / 4`, best evaluation p50/p90은 `17,076 / 130,912`, elapsed p95는 Opta `180,203 ms`, POJO `147,099 ms`다. 집계 POJO 후보 처리량은 609,933 / 1,172,553 ms = **520.2 candidate/s**다. rollback 202,267회, mismatch 0, state corruption 0이다.

하지만 180초는 saturation을 반증했다. fairness seed 1001은 120초 `-7622`에서 180초 `-7624`로 후퇴했고, fairness seed 1002만 `-7622`를 유지했다. preceptor seed 1002는 soft[0]을 `-8160 → -480`으로 크게 개선했지만 soft[1]/soft[2]은 달라졌다. 이는 사전식 W/T/L이 유지돼도 긴 시간으로 안정적·단조 품질 향상이 생기지 않음을 뜻한다. 따라서 240초 확장은 실행하지 않았다.

180초 최종 best의 고유 기여는 ALNS 5건, VND 1건, protected fairness 2건이다. VND 5,048개·fairness 674개 후보는 full-score 검증을 거쳤고 stage·최종 검증 포함 각각 5,056/682회다. 이 profile의 VND/fairness도 조기 수렴하여 후보 elapsed p95가 147초에 그쳤다.

| profile | Opta/POJO feasible | W/T/L | POJO score median | delta median | soft[2] p10/median/p90 | best eval p50/p90 | elapsed p95 Opta/POJO | rollback/mismatch/corruption |
|---|---:|---:|---|---|---:|---:|---:|---:|
| 60초 | 8/8 | 4/4/0 | `[0]hard/[0/0/-7626/0]soft` | `[0,0,0,0,0]` | -1,540 / 0 / 2 | 17,076 / 42,562 | 60,161 / 51,191 ms | 72,006 / 0 / 0 |
| 120초 | 8/8 | 4/4/0 | `[0]hard/[0/0/-7622/0]soft` | `[0,0,0,0,0]` | -1,116 / 0 / 4 | 17,076 / 90,199 | 120,152 / 99,139 ms | 140,263 / 0 / 0 |
| 180초 | 8/8 | 4/4/0 | `[0]hard/[0/0/-7622/0]soft` | `[0,0,0,0,0]` | -1,168 / 0 / 4 | 17,076 / 130,912 | 180,203 / 147,099 ms | 202,267 / 0 / 0 |

## 재현 명령

```bash
cd /Users/brown/.codex/worktrees/c574/every-shift-quarkus

# 3초 pilot/ablation
OUTPUT_DIR="$PWD/benchmark-artifacts/phase6/hybrid-vnd-long/pilot/<timestamp>" \
PARTITION=pilot SEEDS=901,902 DATASETS=fairness.json,preceptor.json,request.json,sample.json \
WALL_SECONDS=3 WARMUP=true scripts/benchmark/run-phase6-hybrid-vnd-long-benchmark.sh wall-3

# 60초 동등 wall-clock 분리 검증
OUTPUT_DIR="$PWD/benchmark-artifacts/phase6/hybrid-vnd-long/validation/wall-60s-<timestamp>" \
PARTITION=validation-wall-60s SEEDS=1001,1002 \
DATASETS=fairness.json,preceptor.json,request.json,sample.json \
CANDIDATES=ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS WALL_SECONDS=60 WARMUP=true \
scripts/benchmark/run-phase6-hybrid-vnd-long-benchmark.sh wall-60

# 120초 동등 wall-clock 분리 검증
OUTPUT_DIR="$PWD/benchmark-artifacts/phase6/hybrid-vnd-long/validation/wall-120s-<timestamp>" \
PARTITION=validation-wall-120s SEEDS=1001,1002 \
DATASETS=fairness.json,preceptor.json,request.json,sample.json \
CANDIDATES=ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS WALL_SECONDS=120 WARMUP=true \
scripts/benchmark/run-phase6-hybrid-vnd-long-benchmark.sh wall-120

# 180초 extended 동등 wall-clock 검증(120초에서도 실제 final best가 개선됐을 때만)
OUTPUT_DIR="$PWD/benchmark-artifacts/phase6/hybrid-vnd-long/validation/wall-180s-<timestamp>" \
PARTITION=validation-wall-180s SEEDS=1001,1002 \
DATASETS=fairness.json,preceptor.json,request.json,sample.json \
CANDIDATES=ALNS_THEN_ORDERED_VND_PROTECTED_FAIRNESS WALL_SECONDS=180 WARMUP=true \
scripts/benchmark/run-phase6-hybrid-vnd-long-benchmark.sh wall-180
```

## 다음 의사결정

후보는 test-only로 보류한다. 60/120/180초 모두 4승 4무 0패였지만 2개 seed × 4입력만으로는 일반화할 수 없고, 180초가 120초보다 안정적으로 좋아지지 않았다. 이번 결과는 Phase 0 승격 gate도, production 활성화 근거도 아니다. 따라서 **Phase 7 승격/확대 benchmark도 사용자 gate 합의 전에는 보류**한다. 다음 단계에는 사용자가 합의한 gate, 더 넓고 잠기지 않은 분리 seed, 그리고 saturation 병목(ALNS 후 VND/fairness의 빠른 수렴과 20초 이상 unused wall time)을 함께 검토해야 한다. 긴 80-pair holdout은 사용자 gate 합의 전 실행하지 않았다.
