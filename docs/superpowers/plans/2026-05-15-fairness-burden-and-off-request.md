# 야간/휴일 누적 공정성 및 Off 요청 가중치 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 연간 야간/휴일 누적 통계와 휴일 캘린더를 반영해 불리한 근무 부담을 공정하게 분산하고, Off 요청은 연간 요청 수가 적은 직원을 더 강하게 보호한다.

**Architecture:** `PlanningRequest`에 휴일 캘린더와 직원별 연간 통계를 optional 입력으로 추가하고, 변환 단계에서 이를 `Employee`와 `Shift`의 solver용 파생 필드로 주입한다. 제약 Provider는 기존 Off 요청 제약의 penalty weight를 직원별 연간 Off 요청 수로 조정하고, 야간/휴일 부담 공정성을 별도 soft level로 추가한다. 저장/API 계층은 늘어난 `BendableScore` soft index를 명시 필드로 매핑해 점수 관측성을 유지한다.

**Tech Stack:** Java 21, Quarkus 3.15.1, Maven Wrapper, OptaPlanner 10.0.0 `BendableScore`, JUnit 5, OptaPlanner `ConstraintVerifier`, Jackson `@JsonProperty`, Firestore document mapping.

---

## 구현 전 규칙

- 이 계획은 `@superpowers:subagent-driven-development` 또는 `@superpowers:executing-plans`로 작업 단위별 실행한다.
- 각 Task는 실패 테스트를 먼저 작성하고 실패를 확인한 뒤 최소 구현으로 통과시킨다.
- 각 Task 완료 후 작은 커밋을 만든다. 서로 다른 Task의 변경을 한 커밋에 섞지 않는다.
- 기존 JSON 요청과 테스트 호환을 깨지 않는다. 신규 필드는 optional이며 누락 시 빈 리스트/0 값으로 동작해야 한다.
- 문서 리뷰 관점의 핵심 보강점은 다음이다: 기존 초안은 방향은 맞지만 파일별 책임, TDD 단계, exact command, score index contract, 후방 호환 경계가 부족했다. 아래 계획은 그 빈틈을 실행 단위로 고정한다.

## Scope Check

이 계획은 하나의 응집된 변경이다: 야간/휴일 부담 공정성 계산과 Off 요청 보호 가중치. 다음은 범위 밖이다.

- solver 알고리즘 자체의 termination 정책 변경
- Cloud Run 배포 스크립트 변경
- Firestore 기존 문서 migration
- `undesirable` 입력을 Desired 요청까지 포괄하는 새 요청 모델로 재설계
- 주말/공휴일 정책을 테넌트별 설정으로 외부화

## 파일 구조와 책임

- Modify: `src/main/java/org/acme/api/dto/PlanningRequest.java`
  - 요청 DTO에 `publicHolidays`, `yearlyEmployeeStats` optional 필드를 추가한다.
  - 기존 5-인자 생성자를 유지해 기존 테스트와 호출 코드를 보호한다.
  - 신규 nested record `PublicHolidayInfo`, `YearlyEmployeeStatsInfo`를 정의한다.
- Modify: `src/main/java/org/acme/util/RequestValidator.java`
  - `yearlyEmployeeStats.employee_id`가 `employees`에 존재하는지 검증한다.
  - `publicHolidays.date` 누락을 검증한다.
- Create: `src/test/java/org/acme/util/RequestValidatorTest.java`
  - 신규 optional 필드의 유효성 검증과 후방 호환을 고정한다.
- Modify: `src/main/java/org/acme/model/Employee.java`
  - 연간 야간/휴일/Off 요청 통계와 Off 요청 penalty weight를 보유한다.
- Modify: `src/main/java/org/acme/model/Shift.java`
  - solver 제약에서 사용할 `fairnessBurdenScore`를 보유한다.
- Create: `src/main/java/org/acme/converter/FairnessBurdenCalculator.java`
  - 생성 기간 내 shift의 야간/휴일 부담 점수를 계산하는 단일 책임 컴포넌트다.
  - `ShiftDateMatcher.resolveLogicalDate(shift)`를 사용해 야간 근무의 논리 날짜를 일관되게 판정한다.
- Modify: `src/main/java/org/acme/converter/EmployeeScheduleBuilder.java`
  - 직원 생성 후 연간 통계를 `Employee`에 반영한다.
  - 모든 phase가 끝난 뒤 `FairnessBurdenCalculator`로 `Shift.fairnessBurdenScore`를 채운다.
- Modify: `src/test/java/org/acme/solver/util/DtoConverterTest.java`
  - JSON 변환 결과에 연간 통계와 부담 점수가 반영되는지 검증한다.
- Modify: `src/test/resources/json/fairness.json`
  - 신규 스키마와 금/토/일/공휴일 계산 경계가 담긴 샘플 요청으로 사용한다.
- Modify: `src/main/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProvider.java`
  - soft level을 6에서 7로 늘린다.
  - Off 요청 penalty에 `employee.offRequestPenaltyWeight`를 곱한다.
  - 신규 `yearlyNightHolidayBurdenFairness` 제약을 추가한다.
- Modify: `src/test/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProviderTest.java`
  - Off 요청 가중치, 신규 부담 공정성, 전체 soft index 기대값을 고정한다.
- Modify: `src/main/java/org/acme/model/JobExecution.java`
  - Firestore에서 읽을 `burdenFairnessSoftScore` 필드를 추가한다.
- Modify: `src/main/java/org/acme/service/JobExecutionService.java`
  - `BendableScore soft[4]`를 `burdenFairnessSoftScore`로 저장하고 기존 fair/desired index를 한 칸씩 이동한다.
- Modify: `src/test/java/org/acme/service/JobExecutionServiceConfigTest.java`
  - 7칸 soft score 저장 매핑을 고정한다.
- Modify: `src/main/java/org/acme/api/dto/StatusResponse.java`
  - status API score 응답에 `burden_fairness_soft_score`를 추가한다.
- Modify: `src/test/java/org/acme/api/dto/StatusResponseTest.java`
  - 신규 score 필드와 JSON 이름을 검증한다.
- Optional Modify: `docs/API_DOCUMENTATION.md`
  - 현재 운영 API 문서로 사용 중이면 status score 응답 예시를 신규 필드와 맞춘다. 단순 과거 참고 문서이면 수정하지 않는다.

## 입력 계약

요청 DTO에 다음 optional 필드를 추가한다. JSON에 없거나 `null`이면 빈 리스트로 처리한다.

```json
{
  "publicHolidays": [
    {
      "date": "2025-12-25",
      "dayOfWeek": "THURSDAY",
      "dayName": "목요일",
      "kind": "PUBLIC_HOLIDAY"
    }
  ],
  "yearlyEmployeeStats": [
    {
      "employee_id": "E1",
      "nightWorkCount": 10,
      "holidayWorkCount": 6,
      "offRequestCount": 2
    }
  ]
}
```

정책:

- `publicHolidays.date`만 solver 계산에 사용한다. `dayOfWeek`, `dayName`, `kind`는 입력 설명과 향후 확장용이다.
- `yearlyEmployeeStats`가 없는 직원은 `nightWorkCount=0`, `holidayWorkCount=0`, `offRequestCount=0`으로 처리한다.
- 중복된 `yearlyEmployeeStats.employee_id`는 마지막 항목을 사용한다. validator는 중복을 오류로 만들지 않는다.
- 알 수 없는 `employee_id`가 `yearlyEmployeeStats`에 있으면 `RequestValidator`에서 오류로 보고, builder는 방어적으로 무시한다.

## 부담 점수 계약

`Shift.fairnessBurdenScore`는 생성 기간 안의 shift에만 계산한다. 생성 기간은 다음처럼 inclusive/exclusive로 본다.

```java
LocalDate startInclusive = scheduleState.getFirstDraftDate();
LocalDate endExclusive = startInclusive.plusDays(scheduleState.getDraftLength());
```

날짜 판정:

- 야간(`N`)은 `ShiftDateMatcher.resolveLogicalDate(shift)`를 사용한다.
- 비야간(`D`, `E`)은 `shift.getStart().toLocalDate()`를 사용한다.
- 생성 기간 밖, `shiftCode == null`, 알 수 없는 shiftCode는 부담 점수 `0`이다.

부담 점수:

```text
평일 D/E = 0
평일 N = 1
금요일 N = 2  (야간 1 + 휴일성 부담 1)
토요일 D/E = 1
토요일 N = 2  (야간 1 + 휴일성 부담 1)
일요일 D/E = 1
일요일 N = 1  (야간 1, 휴일성 부담 없음)
평일 publicHoliday D/E = 1
평일 publicHoliday N = 2  (야간 1 + 휴일성 부담 1)
```

동일 날짜가 주말이면서 `publicHolidays`에도 포함되어도 휴일성 부담은 한 번만 더한다. 즉 holiday component는 `0` 또는 `1`이다.

## Score Index Contract

```text
BendableScore hard[0] -> Firestore hardScore -> API hard_score
BendableScore soft[0] -> Firestore night48RestSoftScore -> API night48_rest_soft_score
BendableScore soft[1] -> Firestore night32RestSoftScore -> API night32_rest_soft_score
BendableScore soft[2] -> Firestore undesiredSoftScore -> API undesired_soft_score
BendableScore soft[3] -> Firestore threeConsecutiveNightSoftScore is not persisted separately
BendableScore soft[4] -> Firestore burdenFairnessSoftScore -> API burden_fairness_soft_score
BendableScore soft[5] -> Firestore fairSoftScore -> API fair_soft_score
BendableScore soft[6] -> Firestore desiredSoftScore -> API desired_soft_score
legacy softScore -> API legacy_soft_score_total
```

주의: 기존 `three consecutive night` soft score는 지금도 별도 저장 필드가 없다. 이 계획에서 새 Firestore 필드를 추가하는 대상은 `burdenFairnessSoftScore`뿐이다.

## Task 1: 요청 DTO와 validator에 신규 입력 계약 추가

**Files:**
- Modify: `src/main/java/org/acme/api/dto/PlanningRequest.java`
- Modify: `src/main/java/org/acme/util/RequestValidator.java`
- Create: `src/test/java/org/acme/util/RequestValidatorTest.java`

- [ ] **Step 1: optional 필드 후방 호환 테스트 작성**

`RequestValidatorTest`를 만들고 기존 5-인자 `PlanningRequest` 생성자가 계속 동작하는지 검증한다.

```java
@Test
void validateAllowsMissingFairnessOptionalInputs() throws Exception {
    PlanningRequest request = baseRequest();

    RequestValidator.validate(request);

    assertEquals(List.of(), request.publicHolidays());
    assertEquals(List.of(), request.yearlyEmployeeStats());
}
```

`baseRequest()` helper는 최소 유효 요청을 반환한다.

```java
private PlanningRequest baseRequest() {
    PlanningRequest.OrganizationInfo organization = new PlanningRequest.OrganizationInfo(
            "tenant-1",
            "테스트 조직",
            "hospital",
            List.of(new PlanningRequest.ShiftInfo("shift-d", "D", "Day", LocalTime.of(8, 0), LocalTime.of(16, 0))),
            LocalDate.of(2025, 11, 30),
            0,
            LocalDate.of(2025, 12, 1),
            7);
    PlanningRequest.EmployeeInfo employee = new PlanningRequest.EmployeeInfo(
            "E1",
            "직원1",
            Set.of("D"),
            Set.of("ALL"));
    PlanningRequest.RequirementInfo requirement = new PlanningRequest.RequirementInfo("shift-d", 0, 1);
    return new PlanningRequest(organization, List.of(employee), List.of(), List.of(), List.of(requirement));
}
```

- [ ] **Step 2: unknown yearly employee 테스트 작성**

```java
@Test
void validateRejectsUnknownEmployeeInYearlyStats() {
    PlanningRequest request = new PlanningRequest(
            baseRequest().organization(),
            baseRequest().employees(),
            baseRequest().history(),
            baseRequest().undesirable(),
            baseRequest().requirements(),
            List.of(),
            List.of(new PlanningRequest.YearlyEmployeeStatsInfo("UNKNOWN", 1, 1, 1)));

    RequestValidator.ValidationException exception = assertThrows(
            RequestValidator.ValidationException.class,
            () -> RequestValidator.validate(request));

    assertTrue(exception.getMessage().contains("yearlyEmployeeStats[0].employee_id must exist in employees list"));
}
```

- [ ] **Step 3: public holiday date 누락 테스트 작성**

```java
@Test
void validateRejectsHolidayWithoutDate() {
    PlanningRequest request = new PlanningRequest(
            baseRequest().organization(),
            baseRequest().employees(),
            baseRequest().history(),
            baseRequest().undesirable(),
            baseRequest().requirements(),
            List.of(new PlanningRequest.PublicHolidayInfo(null, "THURSDAY", "목요일", "PUBLIC_HOLIDAY")),
            List.of());

    RequestValidator.ValidationException exception = assertThrows(
            RequestValidator.ValidationException.class,
            () -> RequestValidator.validate(request));

    assertTrue(exception.getMessage().contains("publicHolidays[0].date is required"));
}
```

- [ ] **Step 4: focused validator test 실패 확인**

Run:

```bash
./mvnw test -Dtest=RequestValidatorTest
```

Expected before implementation: FAIL. `publicHolidays()`, `yearlyEmployeeStats()`, nested record가 아직 없다.

- [ ] **Step 5: `PlanningRequest` record 필드 추가**

record 파라미터 끝에 두 필드를 추가한다.

```java
List<RequirementInfo> requirements,

List<PublicHolidayInfo> publicHolidays,

List<YearlyEmployeeStatsInfo> yearlyEmployeeStats) {
```

compact canonical constructor와 5-인자 호환 생성자를 추가한다.

```java
public PlanningRequest {
    if (publicHolidays == null) {
        publicHolidays = List.of();
    }
    if (yearlyEmployeeStats == null) {
        yearlyEmployeeStats = List.of();
    }
}

public PlanningRequest(
        OrganizationInfo organization,
        List<EmployeeInfo> employees,
        List<AssignmentInfo> history,
        List<AssignmentInfo> undesirable,
        List<RequirementInfo> requirements) {
    this(organization, employees, history, undesirable, requirements, List.of(), List.of());
}
```

- [ ] **Step 6: 신규 nested record 추가**

`RequirementInfo` 아래에 추가한다.

```java
@RegisterForReflection
public record PublicHolidayInfo(
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate date,
        String dayOfWeek,
        String dayName,
        String kind) {
}

@RegisterForReflection
public record YearlyEmployeeStatsInfo(
        @JsonProperty("employee_id") String employeeId,
        int nightWorkCount,
        int holidayWorkCount,
        int offRequestCount) {
}
```

- [ ] **Step 7: `RequestValidator`에 신규 검증 추가**

`knownEmployeeIds` 생성 이후와 requirements 검증 이전에 다음 정책을 반영한다.

```java
if (request.publicHolidays() != null) {
    for (int i = 0; i < request.publicHolidays().size(); i++) {
        PlanningRequest.PublicHolidayInfo holiday = request.publicHolidays().get(i);
        if (holiday == null) {
            errors.add("publicHolidays[" + i + "] cannot be null");
            continue;
        }
        if (holiday.date() == null) {
            errors.add("publicHolidays[" + i + "].date is required");
        }
    }
}

if (request.yearlyEmployeeStats() != null) {
    for (int i = 0; i < request.yearlyEmployeeStats().size(); i++) {
        PlanningRequest.YearlyEmployeeStatsInfo stats = request.yearlyEmployeeStats().get(i);
        if (stats == null) {
            errors.add("yearlyEmployeeStats[" + i + "] cannot be null");
            continue;
        }
        if (stats.employeeId() == null || stats.employeeId().isBlank()) {
            errors.add("yearlyEmployeeStats[" + i + "].employee_id is required");
        } else if (!knownEmployeeIds.isEmpty() && !knownEmployeeIds.contains(stats.employeeId())) {
            errors.add("yearlyEmployeeStats[" + i + "].employee_id must exist in employees list");
        }
    }
}
```

- [ ] **Step 8: focused validator test 통과 확인**

Run:

```bash
./mvnw test -Dtest=RequestValidatorTest
```

Expected after implementation: PASS.

- [ ] **Step 9: Task 1 커밋**

```bash
git add src/main/java/org/acme/api/dto/PlanningRequest.java src/main/java/org/acme/util/RequestValidator.java src/test/java/org/acme/util/RequestValidatorTest.java
git commit -m "feat: accept fairness calendar and yearly stats input"
```

## Task 2: 도메인 모델에 solver용 파생 필드 추가

**Files:**
- Modify: `src/main/java/org/acme/model/Employee.java`
- Modify: `src/main/java/org/acme/model/Shift.java`
- Modify: `src/test/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProviderTest.java`

- [ ] **Step 1: 모델 필드를 사용하는 failing constraint test 추가**

`EmployeeSchedulingConstraintProviderTest`에 다음 테스트를 추가한다. 아직 getter/setter가 없어 컴파일 실패해야 한다.

```java
@Test
void yearlyNightHolidayBurdenFairness_UsesYearlyAndCurrentBurden() {
    Employee employee = createEmployee("E1");
    employee.setYearlyNightWorkCount(2);
    employee.setYearlyHolidayWorkCount(3);

    Shift shift = createShift(1L, employee, LocalDate.of(2025, 12, 25), 9, 17);
    shift.setFairnessBurdenScore(4);

    constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::yearlyNightHolidayBurdenFairness)
            .given(shift)
            .penalizesBy(81); // (2 + 3 + 4)^2
}
```

- [ ] **Step 2: focused constraint test 실패 확인**

Run:

```bash
./mvnw test -Dtest=EmployeeSchedulingConstraintProviderTest#yearlyNightHolidayBurdenFairness_UsesYearlyAndCurrentBurden
```

Expected before implementation: FAIL at compile phase. `setYearlyNightWorkCount`, `setFairnessBurdenScore`, 신규 constraint method가 아직 없다.

- [ ] **Step 3: `Employee`에 연간 통계 필드 추가**

필드 영역에 추가한다.

```java
int yearlyNightWorkCount;
int yearlyHolidayWorkCount;
int yearlyOffRequestCount;
int offRequestPenaltyWeight = 1;
```

각 필드 getter/setter를 추가한다. setter는 음수 입력을 방어적으로 `0`으로 보정한다.

```java
public int getYearlyNightWorkCount() {
    return yearlyNightWorkCount;
}

public void setYearlyNightWorkCount(int yearlyNightWorkCount) {
    this.yearlyNightWorkCount = Math.max(0, yearlyNightWorkCount);
}
```

동일 패턴으로 `yearlyHolidayWorkCount`, `yearlyOffRequestCount`를 추가한다. `offRequestPenaltyWeight` setter는 최소 `1`을 보장한다.

```java
public int getOffRequestPenaltyWeight() {
    return offRequestPenaltyWeight;
}

public void setOffRequestPenaltyWeight(int offRequestPenaltyWeight) {
    this.offRequestPenaltyWeight = Math.max(1, offRequestPenaltyWeight);
}
```

- [ ] **Step 4: `Shift`에 부담 점수 필드 추가**

필드 영역에 추가한다.

```java
int fairnessBurdenScore;
```

getter/setter를 추가한다. setter는 음수 입력을 `0`으로 보정한다.

```java
public int getFairnessBurdenScore() {
    return fairnessBurdenScore;
}

public void setFairnessBurdenScore(int fairnessBurdenScore) {
    this.fairnessBurdenScore = Math.max(0, fairnessBurdenScore);
}
```

- [ ] **Step 5: 신규 constraint skeleton 추가**

`EmployeeSchedulingConstraintProvider`에 다음 method를 임시로 추가한다. 실제 score index와 전체 constraint array 연결은 Task 4에서 완료한다.

```java
Constraint yearlyNightHolidayBurdenFairness(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Shift.class)
            .filter(shift -> shift.getFairnessBurdenScore() > 0)
            .groupBy(Shift::getEmployee, ConstraintCollectors.sum(Shift::getFairnessBurdenScore))
            .penalize(ONE_SOFT_FAIR,
                    (employee, currentBurden) -> {
                        int totalBurden = employee.getYearlyNightWorkCount()
                                + employee.getYearlyHolidayWorkCount()
                                + currentBurden;
                        return totalBurden * totalBurden;
                    })
            .asConstraint("Yearly night/holiday burden fairness");
}
```

이 단계에서는 `ONE_SOFT_FAIR`를 임시 사용한다. soft index 정식 분리는 Task 4에서 처리한다.

- [ ] **Step 6: focused constraint test 통과 확인**

Run:

```bash
./mvnw test -Dtest=EmployeeSchedulingConstraintProviderTest#yearlyNightHolidayBurdenFairness_UsesYearlyAndCurrentBurden
```

Expected after implementation: PASS.

- [ ] **Step 7: Task 2 커밋**

```bash
git add src/main/java/org/acme/model/Employee.java src/main/java/org/acme/model/Shift.java src/main/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProvider.java src/test/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProviderTest.java
git commit -m "feat: add fairness burden fields to solver model"
```

## Task 3: 변환 단계에서 연간 통계와 부담 점수 계산

**Files:**
- Create: `src/main/java/org/acme/converter/FairnessBurdenCalculator.java`
- Modify: `src/main/java/org/acme/converter/EmployeeScheduleBuilder.java`
- Modify: `src/test/java/org/acme/solver/util/DtoConverterTest.java`
- Modify: `src/test/resources/json/fairness.json`

- [ ] **Step 1: `fairness.json` 샘플 요청 작성**

`src/test/resources/json/fairness.json`에 최소 직원 2명, D/E/N shift 정의, `firstDraftDate=2025-12-05`, `draftLength=5`를 포함한다.

필수 날짜 케이스:

```text
2025-12-05 Friday N -> expected burden 2
2025-12-06 Saturday D -> expected burden 1
2025-12-06 Saturday E -> expected burden 1
2025-12-06 Saturday N -> expected burden 2
2025-12-07 Sunday D -> expected burden 1
2025-12-07 Sunday E -> expected burden 1
2025-12-07 Sunday N -> expected burden 1
2025-12-08 Monday publicHoliday N -> expected burden 2
history date before firstDraftDate -> expected burden 0
```

요청에 `yearlyEmployeeStats`도 포함한다.

```json
"yearlyEmployeeStats": [
  {
    "employee_id": "E1",
    "nightWorkCount": 7,
    "holidayWorkCount": 4,
    "offRequestCount": 1
  }
]
```

- [ ] **Step 2: 변환 테스트 작성**

`DtoConverterTest`에 다음 테스트를 추가한다.

```java
@Test
public void testFairnessInputsPopulateEmployeeStatsAndBurdenScores() throws IOException {
    PlanningRequest request = JsonLoader.load("/json/fairness.json", PlanningRequest.class);

    EmployeeSchedule schedule = dtoConverter.convert(request);

    Employee e1 = schedule.getEmployeeList().stream()
            .filter(employee -> employee.getId().equals("E1"))
            .findFirst()
            .orElseThrow();
    Employee e2 = schedule.getEmployeeList().stream()
            .filter(employee -> employee.getId().equals("E2"))
            .findFirst()
            .orElseThrow();

    assertEquals(7, e1.getYearlyNightWorkCount());
    assertEquals(4, e1.getYearlyHolidayWorkCount());
    assertEquals(1, e1.getYearlyOffRequestCount());
    assertEquals(0, e2.getYearlyNightWorkCount());
    assertEquals(1, e2.getOffRequestPenaltyWeight());
}
```

같은 테스트 또는 별도 테스트에서 shift별 부담 점수를 검증한다.

```java
assertBurden(schedule, LocalDate.of(2025, 12, 5), "N", 2);
assertBurden(schedule, LocalDate.of(2025, 12, 6), "D", 1);
assertBurden(schedule, LocalDate.of(2025, 12, 6), "E", 1);
assertBurden(schedule, LocalDate.of(2025, 12, 6), "N", 2);
assertBurden(schedule, LocalDate.of(2025, 12, 7), "D", 1);
assertBurden(schedule, LocalDate.of(2025, 12, 7), "E", 1);
assertBurden(schedule, LocalDate.of(2025, 12, 7), "N", 1);
assertBurden(schedule, LocalDate.of(2025, 12, 8), "N", 2);
```

테스트 helper는 야간 논리 날짜를 기준으로 찾는다.

```java
private void assertBurden(EmployeeSchedule schedule, LocalDate logicalDate, String shiftCode, int expected) {
    Shift shift = schedule.getShiftList().stream()
            .filter(candidate -> shiftCode.equals(candidate.getShiftCode()))
            .filter(candidate -> ShiftDateMatcher.resolveLogicalDate(candidate).equals(logicalDate))
            .findFirst()
            .orElseThrow();
    assertEquals(expected, shift.getFairnessBurdenScore());
}
```

- [ ] **Step 3: focused converter test 실패 확인**

Run:

```bash
./mvnw test -Dtest=DtoConverterTest#testFairnessInputsPopulateEmployeeStatsAndBurdenScores
```

Expected before implementation: FAIL. builder가 연간 통계와 burden score를 아직 주입하지 않는다.

- [ ] **Step 4: `FairnessBurdenCalculator` 생성**

파일을 생성한다.

```java
package org.acme.converter;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import org.acme.api.dto.PlanningRequest;
import org.acme.model.ScheduleState;
import org.acme.model.Shift;
import org.acme.solver.ShiftDateMatcher;

public class FairnessBurdenCalculator {

    public void apply(
            Iterable<Shift> shifts,
            ScheduleState scheduleState,
            Iterable<PlanningRequest.PublicHolidayInfo> publicHolidays) {
        Set<LocalDate> publicHolidayDates = toHolidayDates(publicHolidays);
        LocalDate startInclusive = scheduleState.getFirstDraftDate();
        LocalDate endExclusive = startInclusive.plusDays(scheduleState.getDraftLength());

        for (Shift shift : shifts) {
            shift.setFairnessBurdenScore(calculate(shift, startInclusive, endExclusive, publicHolidayDates));
        }
    }

    int calculate(
            Shift shift,
            LocalDate startInclusive,
            LocalDate endExclusive,
            Set<LocalDate> publicHolidayDates) {
        String shiftCode = normalizeShiftCode(shift);
        if (!"D".equals(shiftCode) && !"E".equals(shiftCode) && !"N".equals(shiftCode)) {
            return 0;
        }

        LocalDate workDate = "N".equals(shiftCode)
                ? ShiftDateMatcher.resolveLogicalDate(shift)
                : shift.getStart().toLocalDate();
        if (workDate.isBefore(startInclusive) || !workDate.isBefore(endExclusive)) {
            return 0;
        }

        int burden = "N".equals(shiftCode) ? 1 : 0;
        if (hasHolidayBurden(shiftCode, workDate, publicHolidayDates)) {
            burden += 1;
        }
        return burden;
    }

    private static Set<LocalDate> toHolidayDates(Iterable<PlanningRequest.PublicHolidayInfo> publicHolidays) {
        Set<LocalDate> dates = new HashSet<>();
        if (publicHolidays == null) {
            return dates;
        }
        for (PlanningRequest.PublicHolidayInfo holiday : publicHolidays) {
            if (holiday != null && holiday.date() != null) {
                dates.add(holiday.date());
            }
        }
        return dates;
    }

    private static boolean hasHolidayBurden(
            String shiftCode,
            LocalDate workDate,
            Set<LocalDate> publicHolidayDates) {
        if (publicHolidayDates.contains(workDate)) {
            return true;
        }
        DayOfWeek dayOfWeek = workDate.getDayOfWeek();
        return switch (shiftCode) {
            case "N" -> dayOfWeek == DayOfWeek.FRIDAY || dayOfWeek == DayOfWeek.SATURDAY;
            case "D", "E" -> dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
            default -> false;
        };
    }

    private static String normalizeShiftCode(Shift shift) {
        return shift.getShiftCode() == null ? "" : shift.getShiftCode().trim().toUpperCase();
    }
}
```

`toHolidayDates`는 `publicHolidays == null`, `holiday == null`, `holiday.date() == null`을 모두 방어적으로 무시한다.

- [ ] **Step 5: `EmployeeScheduleBuilder`에 연간 통계 적용**

필드로 calculator를 추가한다.

```java
private final FairnessBurdenCalculator fairnessBurdenCalculator = new FairnessBurdenCalculator();
```

`mapEmployees` 직후 다음 호출을 넣는다.

```java
applyYearlyEmployeeStats(employeeMap, request.yearlyEmployeeStats());
```

helper를 추가한다.

```java
private void applyYearlyEmployeeStats(
        Map<String, Employee> employeeMap,
        List<PlanningRequest.YearlyEmployeeStatsInfo> yearlyEmployeeStats) {
    int maxOffRequestCount = yearlyEmployeeStats == null ? 0 : yearlyEmployeeStats.stream()
            .filter(stats -> stats != null && stats.employeeId() != null && employeeMap.containsKey(stats.employeeId()))
            .mapToInt(PlanningRequest.YearlyEmployeeStatsInfo::offRequestCount)
            .max()
            .orElse(0);

    if (yearlyEmployeeStats != null) {
        for (PlanningRequest.YearlyEmployeeStatsInfo stats : yearlyEmployeeStats) {
            if (stats == null || stats.employeeId() == null) {
                continue;
            }
            Employee employee = employeeMap.get(stats.employeeId());
            if (employee == null) {
                continue;
            }
            employee.setYearlyNightWorkCount(stats.nightWorkCount());
            employee.setYearlyHolidayWorkCount(stats.holidayWorkCount());
            employee.setYearlyOffRequestCount(stats.offRequestCount());
            employee.setOffRequestPenaltyWeight(maxOffRequestCount - employee.getYearlyOffRequestCount() + 1);
        }
    }

    for (Employee employee : employeeMap.values()) {
        if (employee.getOffRequestPenaltyWeight() < 1) {
            employee.setOffRequestPenaltyWeight(1);
        }
    }
}
```

주의: 통계가 없는 직원의 weight는 기본값 `1`로 유지한다.

- [ ] **Step 6: phase 처리 후 부담 점수 적용**

`undesirablePhaseProcessor.process(...)` 이후, schedule 생성 전에 호출한다.

```java
fairnessBurdenCalculator.apply(finalShiftList, scheduleState, request.publicHolidays());
```

- [ ] **Step 7: focused converter test 통과 확인**

Run:

```bash
./mvnw test -Dtest=DtoConverterTest#testFairnessInputsPopulateEmployeeStatsAndBurdenScores
```

Expected after implementation: PASS.

- [ ] **Step 8: converter 전체 테스트 통과 확인**

Run:

```bash
./mvnw test -Dtest=DtoConverterTest
```

Expected after implementation: PASS. 기존 `sample.json`, `request.json`은 신규 필드 없이도 통과해야 한다.

- [ ] **Step 9: Task 3 커밋**

```bash
git add src/main/java/org/acme/converter/FairnessBurdenCalculator.java src/main/java/org/acme/converter/EmployeeScheduleBuilder.java src/test/java/org/acme/solver/util/DtoConverterTest.java src/test/resources/json/fairness.json
git commit -m "feat: calculate yearly fairness burden inputs"
```

## Task 4: constraint provider에 Off 요청 가중치와 신규 soft level 연결

**Files:**
- Modify: `src/main/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProvider.java`
- Modify: `src/test/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProviderTest.java`

- [ ] **Step 1: Off 요청 가중치 테스트 작성**

```java
@Test
void undesiredDayForEmployee_MultipliesByOffRequestPenaltyWeight() {
    Employee employee = createEmployee("E1");
    employee.setOffRequestPenaltyWeight(4);
    LocalDate date = LocalDate.of(2025, 12, 1);
    Shift shift = createShift(1L, employee, date, 9, 17);
    Availability availability = new Availability(employee, date, AvailabilityType.UNDESIRED);

    constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::undesiredDayForEmployee)
            .given(shift, availability)
            .penalizesBy(1920); // 480 minutes * weight 4
}
```

- [ ] **Step 2: 신규 burden soft index 테스트 작성**

기존 `softScoreLevels_FairOnly`, `softScoreLevels_UndesiredAndFair`, `softScoreLevels_DesiredAndFair` 등 전체 score 기대값을 7칸 배열로 갱신한다. 신규 burden score만 있는 테스트도 추가한다.

```java
@Test
void softScoreLevels_BurdenFairnessOnly() {
    Employee employee = createEmployee("E1");
    employee.setYearlyNightWorkCount(2);
    employee.setYearlyHolidayWorkCount(3);
    Shift shift = createShift(1L, employee, LocalDate.of(2025, 12, 5), 9, 17);
    shift.setFairnessBurdenScore(4);

    constraintVerifier.verifyThat()
            .given(shift)
            .scores(BendableScore.of(new int[] { 0 }, new int[] { 0, 0, 0, 0, -81, -1, 0 }));
}
```

`-1`은 기존 `fairShiftDistribution`의 day shift penalty다. 신규 부담 공정성은 soft index 4, 기존 일반 근무 공정성은 soft index 5다.

- [ ] **Step 3: focused constraint test 실패 확인**

Run:

```bash
./mvnw test -Dtest=EmployeeSchedulingConstraintProviderTest
```

Expected before implementation: FAIL. Off 요청 penalty가 아직 weight를 곱하지 않고, soft level은 아직 6칸이다.

- [ ] **Step 4: soft level 상수와 index 갱신**

상수 영역을 다음 계약으로 갱신한다.

```java
private static final int SOFT_LEVELS = 7;

private static final int SOFT_NIGHT_48H_REST_INDEX = 0;
private static final int SOFT_NIGHT_32H_REST_INDEX = 1;
private static final int SOFT_UNDESIRED_INDEX = 2;
private static final int SOFT_THREE_CONSECUTIVE_NIGHT_INDEX = 3;
private static final int SOFT_BURDEN_FAIRNESS_INDEX = 4;
private static final int SOFT_FAIR_INDEX = 5;
private static final int SOFT_DESIRED_INDEX = 6;
```

신규 score 상수를 추가한다.

```java
private static final BendableScore ONE_SOFT_BURDEN_FAIRNESS = BendableScore.ofSoft(HARD_LEVELS, SOFT_LEVELS,
        SOFT_BURDEN_FAIRNESS_INDEX, 1);
```

- [ ] **Step 5: constraint array에 신규 제약 추가**

soft constraint 순서를 다음처럼 유지한다.

```java
// Soft constraints (우선순위: night48 > night32 > undesired > 3-consecutive-night > burden fairness > fair > desired)
atLeast48HoursAfterTwoConsecutiveNightShifts(constraintFactory),
atLeast32HoursFromNightToNextDayShift(constraintFactory),
undesiredDayForEmployee(constraintFactory),
minimizeThreeConsecutiveNightShifts(constraintFactory),
yearlyNightHolidayBurdenFairness(constraintFactory),
fairShiftDistribution(constraintFactory),
desiredDayForEmployee(constraintFactory)
```

- [ ] **Step 6: Off 요청 penalty weight 적용**

`undesiredDayForEmployee`의 penalty weigher를 다음처럼 변경한다.

```java
.penalize(ONE_SOFT_UNDESIRED,
        shift -> getShiftDurationInMinutes(shift) * shift.getEmployee().getOffRequestPenaltyWeight())
```

기존 `groupBy((shift, availability) -> shift)`는 유지한다. 한 shift가 logical/actual 양쪽 날짜로 매칭되어도 한 번만 penalize되어야 한다.

- [ ] **Step 7: 신규 burden fairness 제약을 정식 score index로 변경**

Task 2에서 임시로 `ONE_SOFT_FAIR`를 사용했다면 다음처럼 바꾼다.

```java
Constraint yearlyNightHolidayBurdenFairness(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Shift.class)
            .filter(shift -> shift.getFairnessBurdenScore() > 0)
            .groupBy(Shift::getEmployee, ConstraintCollectors.sum(Shift::getFairnessBurdenScore))
            .penalize(ONE_SOFT_BURDEN_FAIRNESS,
                    (employee, currentBurden) -> {
                        int totalBurden = employee.getYearlyNightWorkCount()
                                + employee.getYearlyHolidayWorkCount()
                                + currentBurden;
                        return totalBurden * totalBurden;
                    })
            .asConstraint("Yearly night/holiday burden fairness");
}
```

- [ ] **Step 8: 모든 `BendableScore.of(... soft array ...)` 기대값을 7칸으로 갱신**

기존 테스트의 soft 배열에서 old index 4인 `fair` 값을 new index 5로, old index 5인 `desired` 값을 new index 6으로 이동한다. 신규 index 4에는 기존 테스트 대부분 `0`이 들어간다.

예:

```java
// before
BendableScore.of(new int[] { 0 }, new int[] { 0, 0, -480, 0, -1, 0 })

// after
BendableScore.of(new int[] { 0 }, new int[] { 0, 0, -480, 0, 0, -1, 0 })
```

- [ ] **Step 9: focused constraint test 통과 확인**

Run:

```bash
./mvnw test -Dtest=EmployeeSchedulingConstraintProviderTest
```

Expected after implementation: PASS.

- [ ] **Step 10: Task 4 커밋**

```bash
git add src/main/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProvider.java src/test/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProviderTest.java
git commit -m "feat: weight off requests and add burden fairness score"
```

## Task 5: 저장/API score 필드 갱신

**Files:**
- Modify: `src/main/java/org/acme/model/JobExecution.java`
- Modify: `src/main/java/org/acme/service/JobExecutionService.java`
- Modify: `src/test/java/org/acme/service/JobExecutionServiceConfigTest.java`
- Modify: `src/main/java/org/acme/api/dto/StatusResponse.java`
- Modify: `src/test/java/org/acme/api/dto/StatusResponseTest.java`

- [ ] **Step 1: Firestore score 매핑 failing test 갱신**

`JobExecutionServiceConfigTest.extractScoreFieldsMapsBusinessSoftScoresAfterNightPriorityLevels`를 7칸 soft score로 바꾼다.

```java
BendableScore score = BendableScore.of(
        new int[] { 0 },
        new int[] { -7, -30, -120, -3, -81, -5400, 240 });
```

assertion을 추가/수정한다.

```java
assertEquals(-81, fields.get("burdenFairnessSoftScore"));
assertEquals(-5400, fields.get("fairSoftScore"));
assertEquals(240, fields.get("desiredSoftScore"));
```

- [ ] **Step 2: status response failing test 갱신**

`StatusResponseTest.testFrom_MapsBendableScoreFields`에서 job에 신규 필드를 세팅하고 응답을 검증한다.

```java
job.setBurdenFairnessSoftScore(-81);
...
Assertions.assertEquals(-81, response.score().burdenFairnessSoftScore());
```

JSON 이름 테스트를 추가한다.

```java
@Test
void testFrom_SerializesBurdenFairnessScoreJsonName() throws Exception {
    JobExecution job = new JobExecution();
    job.setId("test-id");
    job.setStatus(ExecutionStatus.COMPLETED);
    job.setHardScore(0);
    job.setBurdenFairnessSoftScore(-81);

    StatusResponse response = StatusResponse.from(job, objectMapper);
    String json = objectMapper.writeValueAsString(response);

    Assertions.assertTrue(json.contains("\"burden_fairness_soft_score\":-81"));
}
```

- [ ] **Step 3: focused score API tests 실패 확인**

Run:

```bash
./mvnw test -Dtest=JobExecutionServiceConfigTest,StatusResponseTest
```

Expected before implementation: FAIL. 신규 필드 getter/setter와 score 매핑이 없다.

- [ ] **Step 4: `JobExecution` 필드 추가**

score 필드 영역에 추가한다.

```java
private Integer burdenFairnessSoftScore;
```

getter/setter를 추가한다.

```java
public Integer getBurdenFairnessSoftScore() {
    return burdenFairnessSoftScore;
}

public void setBurdenFairnessSoftScore(Integer burdenFairnessSoftScore) {
    this.burdenFairnessSoftScore = burdenFairnessSoftScore;
}
```

- [ ] **Step 5: `JobExecutionService.extractScoreFields` index 갱신**

```java
static Map<String, Object> extractScoreFields(BendableScore score) {
    Map<String, Object> scoreFields = new HashMap<>();
    scoreFields.put("hardScore", score.hardScore(0));
    scoreFields.put("night48RestSoftScore", score.softScore(0));
    scoreFields.put("night32RestSoftScore", score.softScore(1));
    scoreFields.put("undesiredSoftScore", score.softScore(2));
    scoreFields.put("burdenFairnessSoftScore", score.softScore(4));
    scoreFields.put("fairSoftScore", score.softScore(5));
    scoreFields.put("desiredSoftScore", score.softScore(6));
    return scoreFields;
}
```

- [ ] **Step 6: `StatusResponse.ScoreInfo`에 신규 필드 추가**

`undesiredSoftScore`와 `fairSoftScore` 사이에 추가한다.

```java
@JsonProperty("burden_fairness_soft_score") Integer burdenFairnessSoftScore,
```

`StatusResponse.from(...)`에서 bendable score 생성 시 추가한다.

```java
job.getBurdenFairnessSoftScore(),
```

legacy fallback 생성자 호출에는 해당 위치에 `null`을 넣는다.

- [ ] **Step 7: bendable score 판별에 신규 필드 포함**

`hasBendableSoftScores`에 추가한다.

```java
|| job.getBurdenFairnessSoftScore() != null
```

- [ ] **Step 8: focused score API tests 통과 확인**

Run:

```bash
./mvnw test -Dtest=JobExecutionServiceConfigTest,StatusResponseTest
```

Expected after implementation: PASS.

- [ ] **Step 9: Task 5 커밋**

```bash
git add src/main/java/org/acme/model/JobExecution.java src/main/java/org/acme/service/JobExecutionService.java src/test/java/org/acme/service/JobExecutionServiceConfigTest.java src/main/java/org/acme/api/dto/StatusResponse.java src/test/java/org/acme/api/dto/StatusResponseTest.java
git commit -m "feat: expose burden fairness soft score"
```

## Task 6: 회귀 검증과 문서 정리

**Files:**
- Optional Modify: `docs/API_DOCUMENTATION.md`
- Verify only: full repository tests

- [ ] **Step 1: focused test suite 실행**

Run:

```bash
./mvnw test -Dtest=RequestValidatorTest,DtoConverterTest,EmployeeSchedulingConstraintProviderTest,JobExecutionServiceConfigTest,StatusResponseTest
```

Expected: PASS.

- [ ] **Step 2: 전체 test suite 실행**

Run:

```bash
./mvnw test
```

Expected: PASS.

- [ ] **Step 3: API 문서가 운영 문서인지 확인**

`docs/API_DOCUMENTATION.md`가 status score 응답의 현재 계약을 설명하고 있으면 `burden_fairness_soft_score`를 추가한다. 과거 분석/기록 문서로만 쓰이면 수정하지 않는다.

추가할 score 예시:

```json
"score": {
  "hard_score": 0,
  "night48_rest_soft_score": -7,
  "night32_rest_soft_score": -30,
  "undesired_soft_score": -120,
  "burden_fairness_soft_score": -81,
  "fair_soft_score": -5400,
  "desired_soft_score": 240,
  "legacy_soft_score_total": null
}
```

- [ ] **Step 4: 최종 git diff 검토**

Run:

```bash
git diff --stat
git diff -- docs/superpowers/plans/2026-05-15-fairness-burden-and-off-request.md
```

Expected: 계획 문서는 체크박스 기반 실행 단계, exact file paths, exact commands, expected output을 포함한다. 구현 변경 diff에는 의도한 파일만 포함된다.

- [ ] **Step 5: Task 6 커밋**

문서를 수정하지 않았다면 커밋은 생략한다. 문서를 수정했다면 커밋한다.

```bash
git add docs/API_DOCUMENTATION.md
git commit -m "docs: document burden fairness score field"
```

## 최종 검증 명령

구현 완료 후 다음 명령이 모두 통과해야 한다.

```bash
./mvnw test -Dtest=RequestValidatorTest,DtoConverterTest,EmployeeSchedulingConstraintProviderTest,JobExecutionServiceConfigTest,StatusResponseTest
./mvnw test
```

## 완료 기준

- 기존 JSON 요청은 신규 필드 없이도 deserialize, validate, convert가 성공한다.
- `publicHolidays`, `yearlyEmployeeStats`가 있는 요청은 직원 통계와 shift burden score를 정확히 반영한다.
- Off 요청 penalty는 `shiftDurationMinutes * offRequestPenaltyWeight`로 계산된다.
- 신규 부담 공정성은 soft index 4에만 반영되고, 기존 일반 근무 공정성/desired score는 각각 soft index 5/6으로 이동한다.
- Firestore 저장 필드와 status API 응답에 `burdenFairnessSoftScore` / `burden_fairness_soft_score`가 노출된다.
- `./mvnw test`가 통과한다.

## Assumptions

- `publicHolidays.kind`는 이번 구현에서 계산에 사용하지 않는다.
- 주말 휴일성 부담은 입력 `publicHolidays`와 무관하게 요일 규칙으로 계산한다.
- 공휴일과 주말이 겹치면 휴일성 부담은 중복 가산하지 않는다.
- pinned shift라도 생성 기간 안에 있으면 부담 공정성에는 포함한다.
- 기존처럼 pinned shift는 Off 요청 위반 패널티에서는 제외한다.
- 통계가 없는 직원의 Off 요청 weight는 `1`이다. 통계가 있는 직원은 `maxOffRequestCount - employeeOffRequestCount + 1`로 계산한다.
- `yearlyEmployeeStats`에 음수 값이 들어오면 모델 setter에서 `0`으로 보정한다.
