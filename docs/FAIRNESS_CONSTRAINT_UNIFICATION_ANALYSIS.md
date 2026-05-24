# 공정성 제약조건 위계 통합 계획

> **작성일**: 2026-05-23 (최종 수정: 2026-05-24)  
> **대상 파일**: `EmployeeSchedulingConstraintProvider.java`, `EmployeeSchedule.java`, `Shift.java`, `FairnessBurdenCalculator.java` 등  
> **목적**: `SOFT_BURDEN_FAIRNESS`(Index 4)와 `SOFT_FAIR`(Index 5)를 하나의 Soft Level로 통합하고, 측정 차원을 분리하여 공정성 최적화의 정밀도를 높인다.

> **중요**: 본 프로젝트는 **OptaPlanner 10.0.0**을 사용합니다. 문서에서 인용한 Timefold 문서는 OptaPlanner의 커뮤니티 포크이며, 핵심 개념(`BendableScore`, Constraint Streams 등)은 동일합니다.

---

## 1. 코드베이스 검증 결과

모든 명시된 파일이 실제 코드베이스에서 확인되었으며, 문서 기술과 일치합니다.

| 파일 | 패키지 경로 | 검증 결과 |
|---|---|---|
| `EmployeeSchedule.java` | `org.acme.model` | `@PlanningScore(bendableSoftLevelsSize=7)` 확인됨 |
| `Shift.java` | `org.acme.model` | `int fairnessBurdenScore` 존재, JSON 미직렬화, `toString()` 미포함 |
| `FairnessBurdenCalculator.java` | `org.acme.converter` | 문서 설명과 동일한 계산 로직 확인됨 |
| `EmployeeSchedulingConstraintProvider.java` | `org.acme.solver.algorithm` | `SOFT_LEVELS=7`, Index 0~6 확인됨 |
| `JobExecutionService.java` | `org.acme.service` | `extractScoreFields()` line 231, `softScore(3)` 누락 확인됨 |
| `JobExecution.java` | `org.acme.model` | `burdenFairnessSoftScore` + `fairSoftScore` 필드 존재, `threeConsecutiveNightSoftScore` 부재 |
| `StatusResponse.java` | `org.acme.api.dto` | `ScoreInfo` record에 burdenFairnessSoftScore + fairSoftScore 필드 확인됨 |
| `application.properties` | `src/main/resources` | `best-score-limit=[0]hard/[0/0/0/0/0/0/0]soft` 확인됨 |
| `ShiftPinningFilter.java` | `org.acme.model` | `fairnessBurdenScore` 미사용, migration 무관 |

**참고**: `solverConfig.xml`은 존재하지 않으며, `BendableScore` 설정은 전부 애노테이션/코드 기반입니다.

---

## 2. 현재 구조와 문제점

### 2.1 BendableScore 설정

`EmployeeSchedule.java`:

```java
@PlanningScore(bendableHardLevelsSize = 1, bendableSoftLevelsSize = 7)
BendableScore score;
```

- **Hard Level**: 1개 (Index 0)
- **Soft Level**: 7개 (Index 0~6)

### 2.2 공정성 관련 Constraint 상세

| Soft Index | Constraint | 계산식 | 측정 대상 |
|---|---|---|---|
| 4 | `currentPeriodBurdenFairness` | `(∑fairnessBurdenScore)²` | **N 근무** + **주말·공휴일 근무** (혼합) |
| 5 | `fairShiftDistribution` | `fairWeight × (count)²` | **D/E/N** shiftType별 균등 분배 |

#### 2.2.1 `burden` (Index 4) — `currentPeriodBurdenFairness`

`FairnessBurdenCalculator`가 각 Shift에 `fairnessBurdenScore`(int)를 사전 계산합니다:

```java
// 현재 계산식 (FairnessBurdenCalculator)
burden = (shiftCode == "N" ? 1 : 0)          // N 근무
       + (공휴일 or 주말이면 +1)               // 주말·공휴일 근무
// 결과: N 근무 = 1~2점, D/E 근무 = 0~1점
```

Constraint는 직원별 합계를 제곱하여 penalty로 사용합니다:

```java
.groupBy(Shift::getEmployee, ConstraintCollectors.sum(Shift::getFairnessBurdenScore))
.penalize(ONE_SOFT_BURDEN_FAIRNESS,
    (employee, currentBurden) -> currentBurden * currentBurden)
```

**구조적 문제**: N 근무와 주말·공휴일 근무라는 서로 다른 두 차원이 **하나의 정수로 압축**되어 있습니다. 제곱합 penalty는 합계만 보기 때문에, 다음과 같은 케이스들이 동일한 penalty를 받습니다:

| 케이스 | N 근무 | 주말·공휴일 | burdenSum | penalty |
|---|---|---|---|---|
| A | 10회 | 0회 | 10 | 100 |
| B | 5회 | 5회 | 10 | 100 |
| C | 0회 | 10회 | 10 | 100 |

N 근무에 집중된 케이스(A)와 주말에 집중된 케이스(C), 그리고 균등히 분산된 케이스(B)가 모두 같은 100점으로 평가됩니다. 두 차원의 균형을 구분할 수 없습니다.

#### 2.2.2 `fair` (Index 5) — `fairShiftDistribution`

ShiftType별(D/E/N)로 직원당 개수를 세고, 타입별 가중치를 곱한 제곱 penalty를 적용합니다:

```java
.groupBy(Shift::getEmployee, resolveShiftType, ConstraintCollectors.count())
.penalize(ONE_SOFT_FAIR,
    (employee, shiftType, shiftCount) ->
        getFairWeightByShiftType(shiftType) * shiftCount * shiftCount)
// weight: N=10, E=5, D=1
```

**중복 문제**: N 근무는 Index 4(`burden`)와 Index 5(`fair`) 양쪽에서 동시에 penalty를 받습니다. N 분배가 두 번 측정되는 반면, D/E 분배는 Index 5에서만 측정됩니다.

### 2.3 문제점 요약

1. **차원 혼합**: `burden`의 `fairnessBurdenScore` 하나에 N 근무와 주말·공휴일 두 차원이 섞여 있어, 차원 간 균형을 구분할 수 없음
2. **중복 측정**: N 근무가 Index 4와 5 양쪽에서 페널티 — 동일 목표에 이중 계상
3. **위계 분리**: `burden`(Index 4)과 `fair`(Index 5)가 별도 Soft Level로 분리되어 있어, Timefold 권장사항 위반

> "Do not use a `BendableScore` with seven levels just because you have seven constraints... Usually, **multiple constraints share the same level and are weighted against each other.**"
> — Timefold Official Documentation

---

## 3. Migration 방안: 차원 분리 + Soft Level 축소

**핵심 아이디어**: `BendableScore`는 그대로 두고 `SOFT_LEVELS`를 7→6으로 줄입니다. 통합된 Index 4에 **3개의 독립 constraint**를 배치하여 각 차원을 분리 측정하고, OptaPlanner가 그 합을 최소화하도록 합니다.

### 3.1 Shift 모델 변경

`fairnessBurdenScore`(int) 단일 필드를 두 개로 분리:

```java
// Shift.java — 기존
int fairnessBurdenScore;  // N 근무 + 주말·공휴일 혼합 (0~2)

// Shift.java — 변경
int nightBurdenScore;     // N 근무 여부 (0 or 1)
int holidayBurdenScore;   // 주말·공휴일 근무 여부 (0 or 1)
```

### 3.2 FairnessBurdenCalculator 변경

```java
// 기존: 하나의 값에 두 차원 혼합
int burden = "N".equals(shiftCode) ? 1 : 0;
if (hasHolidayComponent(...)) { burden++; }
shift.setFairnessBurdenScore(burden);

// 변경: 차원별 분리 할당
shift.setNightBurdenScore("N".equals(shiftCode) ? 1 : 0);
shift.setHolidayBurdenScore(hasHolidayComponent(...) ? 1 : 0);
```

계산 규칙:

| 케이스 | nightScore | holidayScore | 설명 |
|---|---|---|---|
| 금요일 N | 1 | 1 | N=1 + 다음날(토)=주말→+1 |
| 토요일 N | 1 | 1 | N=1 + 다음날(일)=주말→+1 |
| 일요일 N | 1 | 0 | N=1 + 다음날(월)=주중→+0 |
| 토요일 D/E | 0 | 1 | D/E=0 + 당일(토)=주말→+1 |
| 수요일 D/E | 0 | 0 | 당일=주중→+0 |

### 3.3 Index 4 = Constraint 3종

`SOFT_FAIRNESS_INDEX = 4`로 통합하고, 세 constraint가 모두 동일한 `ONE_SOFT_FAIRNESS`를 사용합니다.

```java
private static final int SOFT_LEVELS = 6;
private static final int SOFT_FAIRNESS_INDEX = 4;

private static final BendableScore ONE_SOFT_FAIRNESS = BendableScore.ofSoft(
    HARD_LEVELS, SOFT_LEVELS, SOFT_FAIRNESS_INDEX, 1);
```

| Constraint | GroupBy | Penalty | 측정 차원 |
|---|---|---|---|
| `nightShiftFairness` | Employee, sum(nightBurdenScore) | sum² | N 근무 균등 분배 |
| `holidayBurdenFairness` | Employee, sum(holidayBurdenScore) | sum² | 주말·공휴일 근무 균등 분배 |
| `dayEveningShiftFairness` | Employee × shiftType(D/E only), count | weight × count² | D/E 근무 균등 분배 |

**`dayEveningShiftFairness`는 기존 `fairShiftDistribution`에서 N type을 제외한 것입니다.** Night 분배는 `nightShiftFairness`가 전담하므로 중복 측정 문제가 해소됩니다.

### 3.4 차원 분리 효과 검증

앞서 문제였던 세 케이스가 이제 구분됩니다 (N 10회, WH 10회 기준):

| 케이스 | night penalty | holiday penalty | **합계** |
|---|---|---|---|
| A: N 10회, WH 0회 | 10² = 100 | 0² = 0 | **100** |
| B: N 5회, WH 5회 | 5² = 25 | 5² = 25 | **50** |
| C: N 0회, WH 10회 | 0² = 0 | 10² = 100 | **100** |

차원 간 균등 분배된 케이스 B가 가장 낮은 penalty(50)를 받습니다. multiplier 없이 제곱의 수학적 속성만으로 두 차원의 균형이 자연스럽게 유도됩니다.

### 3.5 장단점

**장점**
- multiplier 튜닝 없이 구조적으로 차원 분리 — 정책 변경 시 각 차원 독립 조정 가능
- N 중복 측정 문제 해소
- `BendableScore` 유지로 기존 아키텍처 보존
- 각 차원의 penalty 기여도가 코드에 명시적으로 드러남

**단점**
- `Shift` 필드가 1개 → 2개로 증가 (메모리 영향 미미)
- 여전히 `BendableScore`를 사용하므로 Timefold 최신 권장(`HardSoftBigDecimalScore`)과는 거리 있음 (중장기 전환 과제)

---

## 4. 구체적 구현 사항

### 4.1 차원 분리 상세

Shift 필드 분리 및 계산 규칙은 [3.2](#32-fairnessburdencalculator-변경) 참조. 기존 `FairnessBurdenCalculator`의 `hasHolidayComponent()` 로직은 그대로 유지하되, night/holiday 분리 할당으로 변경합니다.

### 4.2 우선순위 재배치 영향

(BendableScore에서 **낮은 Index = 높은 우선순위**)

| 기존 Index | 기존 제약 | 변경 후 Index | 변경 후 제약 | 우선순위 변화 |
|---|---|---|---|---|
| 0 | night48 | 0 | night48 | — |
| 1 | night32 | 1 | night32 | — |
| 2 | undesired | 2 | undesired | — |
| 3 | 3-consecutive-night | 3 | 3-consecutive-night | — |
| 4 | burden | 4 | **fairness 통합** (3종) | — |
| 5 | fair | 5 | **desired** (기존 6) | ▲ 상승 (6→5) |
| 6 | desired | — | 제거 | — |

**주의**: `desired`의 Index가 6→5로 낮아져 **우선순위가 상승**합니다. 기존에는 모든 soft constraint 중 가장 낮은 우선순위였으나, 변경 후에는 fairness 통합(Index 4)보다 한 단계 낮은 위치로 조정됩니다.

### 4.3 영향받는 파일 목록

| 파일 | 변경 내용 |
|---|---|
| `Shift.java` | `fairnessBurdenScore` → `nightBurdenScore` + `holidayBurdenScore` 분리, getter/setter 추가 |
| `FairnessBurdenCalculator.java` | 두 필드 분리 할당으로 변경. `hasHolidayComponent()` 로직 유지 |
| `EmployeeSchedule.java` | `@PlanningScore(bendableSoftLevelsSize = 6)` |
| `EmployeeSchedulingConstraintProvider.java` | `SOFT_LEVELS=6`, index 상수 재정의, `ONE_SOFT_FAIRNESS` 단일화, constraint 3종 구현 (`nightShiftFairness`, `holidayBurdenFairness`, `dayEveningShiftFairness`). `fairShiftDistribution`에서 N type 제외 |
| `JobExecutionService.java` | `extractScoreFields()`: `burdenFairnessSoftScore` + `fairSoftScore` → `fairnessSoftScore` 통합, 배열 인덱스 재매핑 |
| `JobExecution.java` | `burdenFairnessSoftScore` + `fairSoftScore` 필드 → `fairnessSoftScore` 단일 필드 (Firestore 호환성 주의 — [4.4](#44-firestore-하위-호환성) 참조) |
| `StatusResponse.java` | `ScoreInfo` record: `burdenFairnessSoftScore` + `fairSoftScore` → `fairnessSoftScore`. `hasBendableSoftScores()` 조건 갱신 |
| `application.properties` | `best-score-limit=[0]hard/[0/0/0/0/0/0]soft` (soft 6개) |
| `EmployeeSchedulingConstraintProviderTest.java` | `BendableScore.of(new int[]{0}, new int[]{...})` 7요소 → 6요소 (약 20곳). fairness 통합 index(4)의 expected value 재계산 |
| `SolverRunnerTerminationPolicyTest.java` | `BendableScore.parseScore("[0]hard/[...]soft")` — soft 값 7개 → 6개 |
| `JsonScheduleExporterTest.java` | `BendableScore.of()` / `setScore()` 호출 — 배열 길이 6으로 |
| `JobExecutionServiceConfigTest.java` | `extractScoreFields()` 검증 — 새로운 필드명 및 값으로 갱신 |
| `StatusResponseTest.java` | `testFrom_MapsBendableScoreFields()` — `fairnessSoftScore` 단일 필드 매핑 검증으로 변경 |
| `ScheduleExporterTest.java` | L208: `BendableScore.of(new int[]{0}, new int[]{0,0,0,0,0,-5,0})` → 6요소 |

**영향 없음** (배열 인덱스 직접 접근 없음):
- `ScheduleExportCoordinator.java` — `score.toString()`만 사용
- `SolverRunner.java` — `BendableScore.compareTo()` / `equals()`만 사용

### 4.4 Firestore 하위 호환성

`burdenFairnessSoftScore` + `fairSoftScore` → `fairnessSoftScore` 통합 시, 기존 Firestore 문서 및 API 응답과의 호환성 전략이 필요합니다.

**권장 전략 — 점진적 전환**:

1. `JobExecution.java`: `fairnessSoftScore` 필드 신설, 기존 `burdenFairnessSoftScore` + `fairSoftScore` 필드는 `@Deprecated` 유지
2. `extractScoreFields()`: 신규 `fairnessSoftScore` 저장, 기존 두 필드도 병행 저장 (하위 호환)
3. `StatusResponse.ScoreInfo`: `fairnessSoftScore` + 기존 두 필드 모두 노출 (deprecated 표시)
4. 프론트엔드가 신규 필드로 마이그레이션 완료되면, N주 후 deprecated 필드 제거

### 4.5 참고: softScore(3) 저장 누락 (기존 이슈) — 본 migration에서 반드시 함께 수정

`JobExecutionService.extractScoreFields()`에서 `softScore(3)` (`threeConsecutiveNightShifts`)가 현재 저장되지 않고 있습니다. 이는 이미 확인된 버그이며, 본 migration에서 **반드시 함께 수정**해야 합니다.

**필요한 변경**:
1. `JobExecution.java`: `threeConsecutiveNightSoftScore` 필드 추가
2. `JobExecutionService.extractScoreFields()`: `score.softScore(3)` 매핑 추가
3. `StatusResponse.ScoreInfo`: `threeConsecutiveNightSoftScore` 필드 추가
4. 관련 테스트 파일(`JobExecutionServiceConfigTest`, `StatusResponseTest`) 갱신

이를 함께 수정하지 않으면, soft level 축소(7→6) 후에도 `softScore(3)`가 여전히 저장되지 않는 문제가 남게 됩니다.

---

## 5. 추가 고려사항 및 리스크

### 5.1 `resultJson` 직렬화 하위 호환성 위험

문서 4.4절은 Firestore **문서 필드**(`JobExecution` 엔티티) 하위 호환성만 다룹니다. 하지만 **`resultJson` 난반 직렬화** 위험도 동시에 고려해야 합니다.

- **사실**: `JobExecutionService.saveResult()`는 `EmployeeSchedule` 객체를 JSON 문자열(`resultJson`)로 직렬화하여 Firestore에 저장합니다.
- **위험**: `BendableScore`의 soft level이 7→6으로 변경되면, 기존에 저장된 `resultJson` 내 score 문자열 표현(`[0]hard/[...]soft`)의 soft 배열 길이가 7→6으로 달라집니다.
- **완화 전략**: 기존 `resultJson` 파싱 시 6-level score를 7-level 레거시로 역직렬화하는 fallback 로직을 추가하거나, `resultJson`에 `schemaVersion` 메타필드를 추가하여 6-level/7-level 구분이 가능하도록 합니다.

### 5.2 `best-score-limit` 불일치 위험

`application.properties`의 `solver.termination.best-score-limit`은 `bendableSoftLevelsSize`와 **정확히 일치**해야 합니다.

```properties
# 변경 전
solver.termination.best-score-limit=[0]hard/[0/0/0/0/0/0/0]soft

# 변경 후 (soft 6개)
solver.termination.best-score-limit=[0]hard/[0/0/0/0/0/0]soft
```

**주의**: soft 배열 요소 수가 `bendableSoftLevelsSize`와 불일치하면 solver가 정상 종료되지 않거나 `IllegalArgumentException`이 발생할 수 있습니다.

### 5.3 `Shift.toString()` 정책

현재 `Shift.toString()`은 `fairnessBurdenScore`를 **의도적으로 제외**하고 있습니다. 필드를 `nightBurdenScore` + `holidayBurdenScore`로 분리할 경우, 두 신규 필드도 `toString()`에서 제외할지, 혹은 디버깅 가치를 위해 포함할지 **정책을 명시적으로 결정**해야 합니다.

**권장**: 디버깅 목적으로 포함하되, `toString()`이 너무 길어지지 않도록 간결하게 표현합니다.

### 5.4 `dayEveningShiftFairness` 구현 상세

문서 3.3절은 `dayEveningShiftFairness`가 "기존 `fairShiftDistribution`에서 N type을 제외한 것"이라고만 설명합니다. 실제 Constraint Streams 구현 시 다음과 같이 **`.filter(shift -> !isNightShift(shift))`**를 `groupBy` 전에 추가해야 합니다:

```java
Constraint dayEveningShiftFairness(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Shift.class)
            .filter(shift -> !isNightShift(shift))  // N 근무 제외
            .groupBy(Shift::getEmployee, resolveShiftType, ConstraintCollectors.count())
            .penalize(ONE_SOFT_FAIRNESS,
                    (employee, shiftType, shiftCount) -> getFairWeightByShiftType(shiftType)
                            * shiftCount * shiftCount)
            .asConstraint("Day/evening shift fairness");
}
```

---

## 6. 구현 순서 및 롤백 전략

### 6.1 권장 Phase 구조

| Phase | 작업 내용 | 롤백 트리거 |
|---|---|---|
| **Phase 0: 하위 호환 레이어** | `JobExecution` / `StatusResponse`에 `fairnessSoftScore` 신규 필드 추가, 기존 두 필드 `@Deprecated` 유지. `threeConsecutiveNightSoftScore` 필드도 동시 추가. | Firestore는 스키마리스이므로 DB 마이그레이션 불필요 |
| **Phase 1: 도메인 모델** | `Shift` 필드 분리 (`nightBurdenScore`, `holidayBurdenScore`), `FairnessBurdenCalculator` 갱신, `Shift.toString()` 정책 확정 | 단위 테스트(`ShiftTest`, `FairnessBurdenCalculatorTest`) 실패 시 revert |
| **Phase 2: Solver Core** | `EmployeeSchedule` `@PlanningScore` soft=6 변경, `ConstraintProvider` SOFT_LEVELS=6 및 3종 fairness 구현, `application.properties` best-score-limit 갱신 | `./mvnw -Dtest=EmployeeSchedulingConstraintProviderTest test` 실패 시 revert |
| **Phase 3: 서비스/응답 계층** | `JobExecutionService.extractScoreFields()` 6-level 매핑 + `softScore(3)` 저장 추가. `StatusResponse.ScoreInfo` 6-level 표현 | `./mvnw -Dtest=JobExecutionServiceConfigTest,StatusResponseTest test` 실패 시 revert |
| **Phase 4: 직렬화/수출 계층** | `JsonScheduleExporter`, `ScheduleExporter`, `ScheduleExportCoordinator` 관련 score 문자열/배열 길이 7→6 갱신 | `./mvnw -Dtest=JsonScheduleExporterTest,ScheduleExporterTest test` 실패 시 revert |
| **Phase 5: 통합 검증** | `./mvnw -Dtest=SolverRunnerTest test` 실행 (README 기준). 전체 Cloud Run Job 로컬 실행으로 score 파싱 및 Firestore 저장 검증 | Score 파싱 예외 발생 시 Phase 3~4 재검토 |
| **Phase 6: Deprecated 필드 제거** | 프론트엔드/소비자가 신규 필드로 전환 완료 후, `@Deprecated` 필드 및 `resultJson` 레거시 파싱 로직 제거 | 소비자 팀 확인 완료 기준 |

### 6.2 롤백 전략

- **Cloud Run Job 단위 롤백**: `deploy.sh`는 동일 이미지로 Service와 Job을 모두 배포합니다. Phase 2~5 검증 중 solver 오류 발생 시, 이전 버전 이미지 태그를 가리키는 Cloud Run Job revision을 즉시 복원할 수 있어야 합니다. 배포 직전 `gcloud run jobs revisions list` 기록 및 `--to-revisions` 롤백 명령어를 사전 준비해 둘 것을 권장합니다.
- **Firestore 데이터**: `resultJson`은 immutable 로그 성격이므로, 기존 문서를 그대로 두고 신규 `resultJson`에는 6-level score를 기록합니다. 이전 문서 조회 시 score 문자열의 soft level 개수를 파싱하여 6/7 구분하여 역직렬화하는 유틸리티를 Phase 0에 추가하면 안전합니다.

---

## 7. 테스트 전략 보강

### 7.1 단위 테스트 갱신

문서 4.3절에 나열된 테스트 파일 갱신 외에, 다음 사항을 추가하세요:

1. **README 기준 통합 테스트 명시**
   - `SolverRunnerTest`는 `./mvnw -Dtest=SolverRunnerTest test`로 실행되며, 전체 스케줄 생성 종단(End-to-End) 파이프라인을 검증합니다.
   - "Phase 5 통합 검증"으로 `./mvnw -Dtest=SolverRunnerTest test`를 **필수 통과 기준**으로 기술하세요.

2. **ConstraintProvider fairness penalty 산출 검증**
   - [3.4 차원 분리 효과 검증] 표의 케이스 A/B/C를 **실제 unit test로 구현**할 것을 권장.
   - 예: `EmployeeSchedulingConstraintProviderTest`에 `nightShiftFairness` + `holidayBurdenFairness` 병합 시 케이스 B의 penalty가 50이 되는 assertion 추가.

3. **`softScore(3)` 누락 회귀 방지**
   - `JobExecutionServiceConfigTest`에 `extractScoreFields()`가 `softScore(0)`부터 `softScore(5)`까지 **누락 없이 모두 저장**하는 loop assertion 추가.

### 7.2 Firestore 직렬화 역직렬화 테스트

- `EmployeeSchedule` 7-level score → JSON → 6-level score 역직렬화 compatibility 테스트.
- `JobExecutionService`가 `saveResult()` 후 `getResult()`로 꺼냈을 때 `ScoreInfo`가 올바르게 매핑되는지 확인하는 통합 테스트.

---

## 8. 참고 자료

| 자료 | 링크 |
|---|---|
| Timefold Score Types (BendableScore 경고) | [공식 문서](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/overview#bendableScore) |
| Timefold Load Balancing & Fairness | [공식 문서](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/load-balancing-and-fairness) |
| Timefold Composing Collectors | [공식 문서](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/score-calculation#collectorsComposition) |
| OptaPlanner → Timefold Migration Guide | [공식 문서](https://docs.timefold.ai/timefold-solver/1.x/upgrading-timefold-solver/upgrade-from-optaplanner) |

---

*본 문서는 2026-05-23 최초 작성, 2026-05-24 코드베이스 검증 및 보강 개정되었습니다.*
