# Night Rest Soft Priority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 야간 휴식 관련 2개 규칙을 하드 제약에서 최상위 soft 제약으로 이동해, 일정 생성은 실패시키지 않되 위험한 야간 휴식 패턴에는 강한 점수 페널티를 부여한다.

**Architecture:** 기존 OptaPlanner `BendableScore`의 hard level 1개는 유지하고 soft level을 3개에서 5개로 확장한다. `EmployeeSchedulingConstraintProvider`에서 두 야간 휴식 규칙은 동일한 매칭 및 부족 분(minute) 계산을 유지하되 `Soft[0]`, `Soft[1]`에 배치한다. 최종 검증기와 저장/API score 매핑은 하드 위반 기준과 soft score index 이동을 일관되게 반영한다.

**Tech Stack:** Java 21, Quarkus 3.15.1, Maven Wrapper, OptaPlanner 10 `BendableScore`/Constraint Streams, JUnit 5, Firestore 저장 필드.

---

## 구현 전 확인

- 이 계획은 `@superpowers:subagent-driven-development` 또는 `@superpowers:executing-plans`로 작업 단위별 실행한다.
- 각 Task는 테스트를 먼저 바꾸고 실패를 확인한 뒤 최소 구현으로 통과시킨다.
- 각 Task 완료 후 작은 커밋을 만든다. 서로 다른 Task의 변경을 한 커밋에 섞지 않는다.
- 외부 API 응답 필드명은 유지한다. 단, `undesiredSoftScore`, `fairSoftScore`, `desiredSoftScore`에 저장되는 source index는 각각 `Soft[2]`, `Soft[3]`, `Soft[4]`로 이동한다.

## 파일 구조와 책임

- Modify: `src/main/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProvider.java`
  - `SOFT_LEVELS`를 5로 확장한다.
  - soft score index 상수를 재배치한다.
  - `atLeast48HoursAfterTwoConsecutiveNightShifts`를 `Soft[0]` 페널티로 이동한다.
  - `atLeast32HoursFromNightToNextDayShift`를 `Soft[1]` 페널티로 이동한다.
- Modify: `src/main/java/org/acme/solver/validation/NightHardConstraintValidator.java`
  - 최종 하드 검증에서 32시간/48시간 야간 휴식 검증 호출을 제거한다.
  - 3연속 Night, 월 15회 초과 Night 검증은 유지한다.
- Modify: `src/main/java/org/acme/service/JobExecutionService.java`
  - `undesiredSoftScore`, `fairSoftScore`, `desiredSoftScore` 저장 index를 각각 2, 3, 4로 이동한다.
  - 테스트 가능하도록 score field 추출 로직을 package-private helper로 분리한다.
- Modify: `src/main/resources/application.properties`
  - `solver.termination.best-score-limit`의 soft score shape을 5단계로 변경한다.
- Modify tests:
  - `src/test/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProviderTest.java`
  - `src/test/java/org/acme/solver/validation/NightHardConstraintValidatorTest.java`
  - `src/test/java/org/acme/service/JobExecutionServiceConfigTest.java`
  - `src/test/java/org/acme/solver/SolverRunnerTest.java`
  - `src/test/java/org/acme/solver/SolverRunnerTerminationPolicyTest.java`
  - `src/test/java/org/acme/export/JsonScheduleExporterTest.java`
  - `src/test/java/org/acme/util/ScheduleExporterTest.java`

## 목표 우선순위 모델

```text
Hard[0] 기존 나머지 하드 제약
Soft[0] 2연속 Night 후 다음 근무까지 최소 48시간 휴식 부족분
Soft[1] Night 종료 후 다음 Day 시작까지 최소 32시간 휴식 부족분
Soft[2] UNDESIRED 날짜 배정 패널티
Soft[3] 근무 분배 공정성 패널티
Soft[4] DESIRED 날짜 배정 보상
```

두 야간 휴식 규칙은 더 이상 `ValidationException`으로 Job 성공을 막지 않는다.

## Task 1: Constraint Provider Score Level 변경

**Files:**
- Modify: `src/test/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProviderTest.java`
- Modify: `src/main/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProvider.java`

- [ ] **Step 1: failing test로 score shape 변경**

`EmployeeSchedulingConstraintProviderTest`에서 전체 score를 검증하는 `BendableScore.of(new int[] { ... }, new int[] { ... })` fixture를 5 soft level로 바꾼다. 의미 이동 규칙:

```text
old [undesired, fair, desired]
new [night48, night32, undesired, fair, desired]
```

대표 변경:

```java
// 48시간 규칙 1분 부족 + fair 41점
BendableScore.of(new int[] { 0 }, new int[] { -1, 0, 0, -41, 0 })

// 32시간 규칙 960분 부족 + fair 41점
BendableScore.of(new int[] { 0 }, new int[] { 0, -960, 0, -41, 0 })

// undesired 480분 + fair 1점
BendableScore.of(new int[] { 0 }, new int[] { 0, 0, -480, -1, 0 })

// desired 480분 보상 + fair 1점
BendableScore.of(new int[] { 0 }, new int[] { 0, 0, 0, -1, 480 })
```

다음 테스트는 하드 score가 더 이상 감소하지 않아야 한다:

- `twoConsecutiveNightShiftsRequire48HoursBeforeNextShift_EdgeCase47h59m`
- `twoConsecutiveNightShiftsRequire48HoursBeforeNextShift_AppliesToEveningShift`
- `twoConsecutiveNightShiftsRequire48HoursBeforeNextShift_AppliesToNightShift`

- [ ] **Step 2: focused constraint test 실패 확인**

Run:

```bash
./mvnw test -Dtest=EmployeeSchedulingConstraintProviderTest
```

Expected before implementation: 실패한다. 대표 실패 원인은 현재 provider가 3 soft level을 생성하거나 두 야간 휴식 규칙을 hard score로 penalize하기 때문이다.

- [ ] **Step 3: score level 상수 최소 구현**

`EmployeeSchedulingConstraintProvider` 상단 상수를 다음 형태로 바꾼다.

```java
private static final int HARD_LEVELS = 1;
private static final int SOFT_LEVELS = 5;

private static final int HARD_LEVEL_INDEX = 0;
private static final int SOFT_NIGHT_48H_REST_INDEX = 0;
private static final int SOFT_NIGHT_32H_REST_INDEX = 1;
private static final int SOFT_UNDESIRED_INDEX = 2;
private static final int SOFT_FAIR_INDEX = 3;
private static final int SOFT_DESIRED_INDEX = 4;

private static final BendableScore ONE_HARD = BendableScore.ofHard(HARD_LEVELS, SOFT_LEVELS, HARD_LEVEL_INDEX, 1);
private static final BendableScore ONE_SOFT_NIGHT_48H_REST = BendableScore.ofSoft(
        HARD_LEVELS, SOFT_LEVELS, SOFT_NIGHT_48H_REST_INDEX, 1);
private static final BendableScore ONE_SOFT_NIGHT_32H_REST = BendableScore.ofSoft(
        HARD_LEVELS, SOFT_LEVELS, SOFT_NIGHT_32H_REST_INDEX, 1);
private static final BendableScore ONE_SOFT_UNDESIRED = BendableScore.ofSoft(
        HARD_LEVELS, SOFT_LEVELS, SOFT_UNDESIRED_INDEX, 1);
private static final BendableScore ONE_SOFT_FAIR = BendableScore.ofSoft(
        HARD_LEVELS, SOFT_LEVELS, SOFT_FAIR_INDEX, 1);
private static final BendableScore ONE_SOFT_DESIRED = BendableScore.ofSoft(
        HARD_LEVELS, SOFT_LEVELS, SOFT_DESIRED_INDEX, 1);
```

- [ ] **Step 4: 두 야간 휴식 constraint를 soft 섹션으로 이동**

`defineConstraints`에서 hard 섹션에는 기존 하드 제약만 남긴다.

```java
// Hard constraints
requiredSkill(constraintFactory),
noOverlappingShifts(constraintFactory),
atLeast12HoursBetweenTwoShifts(constraintFactory),
noThreeConsecutiveNightShifts(constraintFactory),
max15NightShiftsPerMonth(constraintFactory),
oneShiftPerDay(constraintFactory),
unavailableEmployee(constraintFactory),
// Soft constraints
atLeast48HoursAfterTwoConsecutiveNightShifts(constraintFactory),
atLeast32HoursFromNightToNextDayShift(constraintFactory),
undesiredDayForEmployee(constraintFactory),
fairShiftDistribution(constraintFactory),
desiredDayForEmployee(constraintFactory)
```

`atLeast32HoursFromNightToNextDayShift`의 penalty score를 바꾼다.

```java
.penalize(ONE_SOFT_NIGHT_32H_REST,
        (nightShift, nextDayStart) -> MIN_NIGHT_TO_NEXT_DAY_REST_MINUTES
                - getMinutesBetween(nightShift.getEnd(), nextDayStart))
```

`atLeast48HoursAfterTwoConsecutiveNightShifts`의 penalty score를 바꾼다.

```java
.penalize(ONE_SOFT_NIGHT_48H_REST,
        (secondNight, nextShiftStart) -> MIN_REST_AFTER_TWO_CONSECUTIVE_NIGHTS_MINUTES
                - getMinutesBetween(secondNight.getEnd(), nextShiftStart))
```

- [ ] **Step 5: focused constraint test 통과 확인**

Run:

```bash
./mvnw test -Dtest=EmployeeSchedulingConstraintProviderTest
```

Expected after implementation: `EmployeeSchedulingConstraintProviderTest` 전체가 통과한다.

- [ ] **Step 6: Task 1 커밋**

```bash
git add src/main/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProvider.java src/test/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProviderTest.java
git commit -m "feat: move night rest constraints to soft score levels"
```

## Task 2: 최종 하드 검증에서 soft 야간 휴식 제외

**Files:**
- Modify: `src/test/java/org/acme/solver/validation/NightHardConstraintValidatorTest.java`
- Modify: `src/main/java/org/acme/solver/validation/NightHardConstraintValidator.java`

- [ ] **Step 1: validator failing test 변경**

다음 두 테스트를 `assertThrows`에서 `assertDoesNotThrow`로 바꾸고 메서드명을 새 동작에 맞게 변경한다.

```java
void validate_allowsDayShiftStartsWithin32HoursAfterNightShiftBecauseItIsSoft()
void validate_allowsNextShiftStartsWithin48HoursAfterTwoConsecutiveNightShiftsBecauseItIsSoft()
```

예상 assertion:

```java
assertDoesNotThrow(() -> validator.validate(shiftsByEmployee, LoggerFactory.getLogger(getClass())));
```

다음 테스트는 계속 `ValidationException`을 기대해야 한다.

- `validate_throwsWhenThreeConsecutiveNightShiftsExist`
- `validate_throwsWhenActualStartMonthHas16NightShifts`

- [ ] **Step 2: focused validator test 실패 확인**

Run:

```bash
./mvnw test -Dtest=NightHardConstraintValidatorTest
```

Expected before implementation: 변경한 두 테스트가 아직 validator에서 예외를 던져 실패한다.

- [ ] **Step 3: hard validation flow 최소 구현**

`NightHardConstraintValidator.validate`에서 다음 호출을 제거한다.

```java
validateNightToNextDayRest(employee, shifts);
validateRestAfterTwoConsecutiveNightShifts(employee, shifts);
```

남아야 하는 호출:

```java
validateNoThreeConsecutiveNightShifts(employee, shifts);
validateMonthlyNightShiftLimit(employee, shifts);
```

클래스 Javadoc도 하드 제약 목록과 맞춘다.

```java
/**
 * 야간 하드 제약 검증을 수행합니다.
 * - 3연속 Night 근무 금지
 * - 직원별 실제 시작월 기준 Night 근무 월 15회 이하
 */
```

private helper는 이번 변경에서 삭제하지 않는다. 향후 진단 로그나 하드 제약 재승격 시 재사용할 수 있고, Java 컴파일은 unused private method를 오류로 처리하지 않는다.

- [ ] **Step 4: focused validator test 통과 확인**

Run:

```bash
./mvnw test -Dtest=NightHardConstraintValidatorTest
```

Expected after implementation: `NightHardConstraintValidatorTest` 전체가 통과한다.

- [ ] **Step 5: Task 2 커밋**

```bash
git add src/main/java/org/acme/solver/validation/NightHardConstraintValidator.java src/test/java/org/acme/solver/validation/NightHardConstraintValidatorTest.java
git commit -m "feat: allow soft night rest violations in final validation"
```

## Task 3: 저장/API score index 매핑 보정

**Files:**
- Modify: `src/test/java/org/acme/service/JobExecutionServiceConfigTest.java`
- Modify: `src/main/java/org/acme/service/JobExecutionService.java`
- Reference only: `src/main/java/org/acme/api/dto/StatusResponse.java`
- Reference only: `src/test/java/org/acme/api/dto/StatusResponseTest.java`

- [ ] **Step 1: score field 매핑 failing test 추가**

`JobExecutionServiceConfigTest`에 5-level `BendableScore`에서 기존 API 저장 필드가 올바른 source index를 읽는지 테스트한다.

```java
@Test
void extractScoreFieldsMapsBusinessSoftScoresAfterNightPriorityLevels() {
    BendableScore score = BendableScore.of(
            new int[] { 0 },
            new int[] { -7, -30, -120, -5400, 240 });

    Map<String, Object> fields = JobExecutionService.extractScoreFields(score);

    assertEquals(0, fields.get("hardScore"));
    assertEquals(-120, fields.get("undesiredSoftScore"));
    assertEquals(-5400, fields.get("fairSoftScore"));
    assertEquals(240, fields.get("desiredSoftScore"));
}
```

필요한 import:

```java
import java.util.Map;
import org.optaplanner.core.api.score.buildin.bendable.BendableScore;
```

- [ ] **Step 2: focused service test 실패 확인**

Run:

```bash
./mvnw test -Dtest=JobExecutionServiceConfigTest
```

Expected before implementation: `extractScoreFields`가 없어서 컴파일 실패하거나, 기존 private mapping이 0/1/2 index를 사용해 assertion이 실패한다.

- [ ] **Step 3: score field helper 최소 구현**

`JobExecutionService`에서 `putScoreFields`를 테스트 가능한 package-private static helper를 사용하도록 변경한다.

```java
static Map<String, Object> extractScoreFields(BendableScore score) {
    Map<String, Object> scoreFields = new HashMap<>();
    scoreFields.put("hardScore", score.hardScore(0));
    scoreFields.put("undesiredSoftScore", score.softScore(2));
    scoreFields.put("fairSoftScore", score.softScore(3));
    scoreFields.put("desiredSoftScore", score.softScore(4));
    return scoreFields;
}

private void putScoreFields(Map<String, Object> updates, BendableScore score) {
    updates.putAll(extractScoreFields(score));
}
```

`StatusResponse`의 JSON 필드명은 변경하지 않는다. 이 Task의 목적은 저장되는 값의 source index를 보정하는 것이다.

- [ ] **Step 4: focused service/API DTO tests 통과 확인**

Run:

```bash
./mvnw test -Dtest=JobExecutionServiceConfigTest,StatusResponseTest
```

Expected after implementation: 두 테스트 클래스가 모두 통과한다.

- [ ] **Step 5: Task 3 커밋**

```bash
git add src/main/java/org/acme/service/JobExecutionService.java src/test/java/org/acme/service/JobExecutionServiceConfigTest.java
git commit -m "fix: map persisted business soft scores after night priorities"
```

## Task 4: 설정과 통합 테스트 기대값 갱신

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/java/org/acme/solver/SolverRunnerTest.java`
- Modify: `src/test/java/org/acme/solver/SolverRunnerTerminationPolicyTest.java`
- Modify: `src/test/java/org/acme/export/JsonScheduleExporterTest.java`
- Modify: `src/test/java/org/acme/util/ScheduleExporterTest.java`

- [ ] **Step 1: best-score-limit shape 변경**

`src/main/resources/application.properties`에서 다음 값을 변경한다.

```properties
solver.termination.best-score-limit=[0]hard/[0/0/0/0/0]soft
```

새 termination 정책은 추가하지 않는다. `SolverRunner`는 현재 spent limit/unimproved spent limit을 프로그램적으로 사용한다.

- [ ] **Step 2: SolverRunnerTest failing expectation 변경**

`SolverRunnerTest`에서 undesired 점수 검증 index를 `Soft[2]`로 이동한다.

```java
assertEquals(expectedUndesiredSoftScore, solution.getScore().softScore(2),
        "softScore(2) should match actual undesired penalty minutes from the solved schedule");
```

다음 assertion은 제거한다.

```java
int nightToNextDayMinimum32HourViolations = countNightToNextDayMinimum32HourViolations(solution);
assertEquals(0, nightToNextDayMinimum32HourViolations,
        "Night 종료 후 다음 Day 시작까지 최소 32시간이 보장되어야 합니다.");
```

해당 helper와 상수도 더 이상 쓰이지 않으면 제거한다.

```java
private static final long MIN_NIGHT_TO_NEXT_DAY_REST_MINUTES = 32L * 60L;
private int countNightToNextDayMinimum32HourViolations(EmployeeSchedule schedule) { ... }
private boolean isDayShift(Shift shift) { ... }
```

3연속 Night 하드 검증은 유지한다.

- [ ] **Step 3: termination policy score string fixture 변경**

`SolverRunnerTerminationPolicyTest`의 parse score fixture를 5 soft level로 변경한다. 기존 fair 개선 의미를 유지하려면 fair index인 `Soft[3]`에 값을 둔다.

```java
BendableScore previousScore = BendableScore.parseScore("[0]hard/[0/0/0/-11/0]soft");
BendableScore currentScore = BendableScore.parseScore("[0]hard/[0/0/0/-10/0]soft");
```

- [ ] **Step 4: export fixture score shape 변경**

`JsonScheduleExporterTest`:

```java
BendableScore.of(new int[] { 0 }, new int[] { 0, 0, -10, 0, 0 })
BendableScore.of(new int[] { 0 }, new int[] { 0, 0, 0, 0, 0 })
```

`ScheduleExporterTest`:

```java
BendableScore.zero(1, 5)
BendableScore.of(new int[] { 0 }, new int[] { 0, 0, 0, -5, 0 })
```

- [ ] **Step 5: focused integration/export tests 실패 확인 후 구현 반영**

Run before implementation:

```bash
./mvnw test -Dtest=SolverRunnerTest,SolverRunnerTerminationPolicyTest,JsonScheduleExporterTest,ScheduleExporterTest
```

Expected before implementation: 3 soft level score fixture 또는 `softScore(0)` 기대 때문에 실패한다.

변경 적용 후 같은 명령을 다시 실행한다.

Expected after implementation: listed tests pass.

- [ ] **Step 6: Task 4 커밋**

```bash
git add src/main/resources/application.properties src/test/java/org/acme/solver/SolverRunnerTest.java src/test/java/org/acme/solver/SolverRunnerTerminationPolicyTest.java src/test/java/org/acme/export/JsonScheduleExporterTest.java src/test/java/org/acme/util/ScheduleExporterTest.java
git commit -m "test: update five-level bendable score fixtures"
```

## Task 5: 전체 검증과 회귀 점검

**Files:**
- No additional source edits expected.

- [ ] **Step 1: score index 잔여 사용 검색**

Run:

```bash
rg -n "softScore\\(|BendableScore\\.of|BendableScore\\.zero|parseScore|\\[0/0/0\\]soft|\\[0/-" src/main/java src/test/java src/main/resources
```

Expected:

- `JobExecutionService`는 business soft scores를 `softScore(2)`, `softScore(3)`, `softScore(4)`에서 읽는다.
- 3-level score fixture가 남아 있지 않다.
- intentionally unchanged인 `StatusResponse` field names 외에 stale naming이 없다.

- [ ] **Step 2: 전체 테스트 실행**

Run:

```bash
./mvnw test
```

Expected: build success, failures 0, errors 0.

- [ ] **Step 3: 최종 diff 검사**

Run:

```bash
git diff -- src/main/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProvider.java src/main/java/org/acme/solver/validation/NightHardConstraintValidator.java src/main/java/org/acme/service/JobExecutionService.java src/main/resources/application.properties src/test/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProviderTest.java src/test/java/org/acme/solver/validation/NightHardConstraintValidatorTest.java src/test/java/org/acme/service/JobExecutionServiceConfigTest.java src/test/java/org/acme/solver/SolverRunnerTest.java src/test/java/org/acme/solver/SolverRunnerTerminationPolicyTest.java src/test/java/org/acme/export/JsonScheduleExporterTest.java src/test/java/org/acme/util/ScheduleExporterTest.java
```

Confirm:

- 두 야간 휴식 규칙만 hard에서 soft로 이동했다.
- 3연속 Night, 월 15회 Night, 스킬, 중복/겹침, 하루 1근무, unavailable hard 제약은 약화되지 않았다.
- `Soft[0]`은 48시간 야간 휴식 부족분, `Soft[1]`은 32시간 Night-to-Day 부족분이다.
- `UNDESIRED`, fairness, `DESIRED`는 각각 `Soft[2]`, `Soft[3]`, `Soft[4]`다.
- 최종 validator는 soft 야간 휴식 위반으로 `ValidationException`을 던지지 않는다.
- Firestore/API용 business soft score 필드는 새 index에서 저장된다.

- [ ] **Step 4: Task 5 커밋**

```bash
git add src/main/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProvider.java src/main/java/org/acme/solver/validation/NightHardConstraintValidator.java src/main/java/org/acme/service/JobExecutionService.java src/main/resources/application.properties src/test/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProviderTest.java src/test/java/org/acme/solver/validation/NightHardConstraintValidatorTest.java src/test/java/org/acme/service/JobExecutionServiceConfigTest.java src/test/java/org/acme/solver/SolverRunnerTest.java src/test/java/org/acme/solver/SolverRunnerTerminationPolicyTest.java src/test/java/org/acme/export/JsonScheduleExporterTest.java src/test/java/org/acme/util/ScheduleExporterTest.java
git commit -m "chore: verify night rest soft priority migration"
```

이 커밋은 Task 1-4 커밋에 누락된 검증 보정이 있을 때만 만든다. 변경이 없다면 만들지 않는다.

## Acceptance Criteria

- 32시간 Night-to-Day 휴식 부족만 있는 schedule은 `ValidationException` 없이 완료될 수 있다.
- 2연속 Night 후 48시간 휴식 부족만 있는 schedule은 `ValidationException` 없이 완료될 수 있다.
- 48시간 휴식 부족분은 `Soft[0]`에 minute 단위 음수 페널티로 반영된다.
- 32시간 휴식 부족분은 `Soft[1]`에 minute 단위 음수 페널티로 반영된다.
- 기존 `UNDESIRED`, fairness, `DESIRED` 동작은 각각 `Soft[2]`, `Soft[3]`, `Soft[4]`에서 유지된다.
- `JobExecutionService`가 저장하는 `undesiredSoftScore`, `fairSoftScore`, `desiredSoftScore`는 새 business soft score index를 사용한다.
- `./mvnw test`가 성공한다.

## Assumptions

- 두 야간 휴식 규칙은 운영상 강한 선호 조건이며, 최종 실패 조건은 아니다.
- `48h after two consecutive Night`가 `32h Night-to-Day`보다 우선순위가 높아 `Soft[0]`을 사용한다.
- 외부 API score field 이름은 호환성을 위해 유지한다.
- night-rest soft score를 별도 API 필드로 노출하는 요구는 이번 범위에 포함하지 않는다. 필요 시 후속 Task로 `night48RestSoftScore`, `night32RestSoftScore` 필드를 설계한다.
- unused private validation helper는 Java 컴파일 오류가 아니므로 이번 변경에서 제거하지 않는다.
