# Night Rest Score Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 32h/48h 야간 휴식 soft score를 Firestore 저장 필드와 `GET /api/status/{id}` 응답에서 명시적으로 관측 가능하게 만든다.

**Architecture:** 기존 OptaPlanner `BendableScore` 정책은 유지하고 `soft[0]`, `soft[1]`를 신규 named score 필드로 저장/API 노출한다. `NightHardConstraintValidator`에는 실제 하드 제약만 남겨 32h/48h 휴식 부족을 hard 실패로 오해할 여지를 제거한다. 기존 business soft score 및 legacy 응답은 후방 호환을 유지한다.

**Tech Stack:** Java 21, Quarkus 3.15.1, Maven Wrapper, OptaPlanner `BendableScore`, JUnit 5, Jackson `@JsonProperty`, Firestore document mapping.

---

## 구현 전 규칙

- 이 계획은 `@superpowers:subagent-driven-development` 또는 `@superpowers:executing-plans`로 작업 단위별 실행한다.
- 각 Task는 테스트를 먼저 바꾸고 실패를 확인한 뒤 최소 구현으로 통과시킨다.
- 각 Task 완료 후 작은 커밋을 만든다. 서로 다른 Task의 변경을 한 커밋에 섞지 않는다.
- 이 계획은 `docs/plans/2026-05-07-night-rest-soft-priority-plan.md`에서 이미 도입된 score index 정책을 관측 가능하게 만드는 후속 작업이다.

## Scope Check

이 계획은 하나의 응집된 변경이다: 야간 휴식 soft score의 저장/API 관측성. Solver constraint 계산 방식, weight 조정, Cloud Run 배포 스크립트, Firestore migration은 범위 밖이다.

## 파일 구조와 책임

- Modify: `src/main/java/org/acme/solver/validation/NightHardConstraintValidator.java`
  - 3연속 Night, 월 15회 Night 초과 검증만 담당한다.
  - 32h/48h 야간 휴식 검증 helper와 관련 상수/import를 제거한다.
- Modify: `src/test/java/org/acme/solver/validation/NightHardConstraintValidatorTest.java`
  - 32h/48h 부족 케이스가 hard validator를 통과한다는 정책을 고정한다.
- Modify: `src/main/java/org/acme/model/JobExecution.java`
  - Firestore 문서에서 읽고 API로 전달할 named score 필드를 보유한다.
- Modify: `src/main/java/org/acme/service/JobExecutionService.java`
  - `BendableScore`를 Firestore 저장용 camelCase 필드로 매핑한다.
- Modify: `src/test/java/org/acme/service/JobExecutionServiceConfigTest.java`
  - `soft[0..4]`의 저장 필드 매핑을 고정한다.
- Modify: `src/main/java/org/acme/api/dto/StatusResponse.java`
  - Firestore camelCase 필드를 status API의 snake_case JSON 필드로 노출한다.
- Modify: `src/test/java/org/acme/api/dto/StatusResponseTest.java`
  - 신규 필드, legacy fallback, night-only bendable 판별을 고정한다.
- Modify: `docs/API_DOCUMENTATION.md`
  - status 응답 예시, score 설명, TypeScript parser 예시를 신규 필드와 맞춘다.
- Optional Modify: `docs/WORK_SUMMARY.md`
  - 현재 운영 문서로 쓰이고 있으면 동일한 score 필드 설명을 맞춘다. 단순 과거 작업 기록이면 수정하지 않는다.

## Score Field Contract

```text
BendableScore hard[0] -> Firestore hardScore -> API hard_score
BendableScore soft[0] -> Firestore night48RestSoftScore -> API night48_rest_soft_score
BendableScore soft[1] -> Firestore night32RestSoftScore -> API night32_rest_soft_score
BendableScore soft[2] -> Firestore undesiredSoftScore -> API undesired_soft_score
BendableScore soft[3] -> Firestore fairSoftScore -> API fair_soft_score
BendableScore soft[4] -> Firestore desiredSoftScore -> API desired_soft_score
legacy softScore -> API legacy_soft_score_total
```

## Task 1: Night hard validator에서 soft helper 제거

**Files:**
- Modify: `src/main/java/org/acme/solver/validation/NightHardConstraintValidator.java`
- Test: `src/test/java/org/acme/solver/validation/NightHardConstraintValidatorTest.java`

- [x] **Step 1: 기존 soft 정책 테스트 확인**

확인할 테스트명:

```java
void validate_allowsDayShiftStartsWithin32HoursAfterNightShiftBecauseItIsSoft()
void validate_allowsNextShiftStartsWithin48HoursAfterTwoConsecutiveNightShiftsBecauseItIsSoft()
```

두 테스트는 이미 `assertDoesNotThrow`를 사용해야 한다. 다르면 먼저 이 형태로 고친다.

```java
assertDoesNotThrow(() -> validator.validate(shiftsByEmployee, LoggerFactory.getLogger(getClass())));
```

- [x] **Step 2: focused validator test baseline 확인**

Run:

```bash
./mvnw test -Dtest=NightHardConstraintValidatorTest
```

Expected before implementation: PASS. 현재 호출 경로에서 32h/48h helper는 사용되지 않지만, 미사용 코드가 hard 정책으로 오해될 수 있는 상태다.

- [x] **Step 3: 미사용 32h/48h helper 삭제**

`NightHardConstraintValidator`에서 다음 private method 전체를 삭제한다.

```java
private void validateNightToNextDayRest(Employee employee, List<Shift> shifts) {
    ...
}

private void validateRestAfterTwoConsecutiveNightShifts(Employee employee, List<Shift> shifts) {
    ...
}
```

- [x] **Step 4: 관련 import, 상수, helper 삭제**

삭제할 import:

```java
import java.time.Duration;
import java.time.LocalDateTime;
```

삭제할 상수:

```java
private static final int MIN_NIGHT_TO_NEXT_DAY_REST_MINUTES = 32 * 60;
private static final int MIN_REST_AFTER_TWO_CONSECUTIVE_NIGHTS_MINUTES = 48 * 60;
private static final String SHIFT_TYPE_DAY = "D";
```

삭제할 helper:

```java
private boolean isDayShift(Shift shift) {
    return SHIFT_TYPE_DAY.equals(normalizeShiftCode(shift));
}
```

- [x] **Step 5: focused validator test 통과 확인**

Run:

```bash
./mvnw test -Dtest=NightHardConstraintValidatorTest
```

Expected after implementation: PASS. 특히 다음은 계속 PASS여야 한다.

```text
validate_throwsWhenThreeConsecutiveNightShiftsExist
validate_allowsDayShiftStartsWithin32HoursAfterNightShiftBecauseItIsSoft
validate_allowsNextShiftStartsWithin48HoursAfterTwoConsecutiveNightShiftsBecauseItIsSoft
validate_throwsWhenActualStartMonthHas16NightShifts
```

- [x] **Step 6: Task 1 커밋**

```bash
git add src/main/java/org/acme/solver/validation/NightHardConstraintValidator.java src/test/java/org/acme/solver/validation/NightHardConstraintValidatorTest.java
git commit -m "refactor: remove soft night rest checks from hard validator"
```

## Task 2: Firestore 저장용 night-rest score 필드 추가

**Files:**
- Modify: `src/main/java/org/acme/model/JobExecution.java`
- Modify: `src/test/java/org/acme/service/JobExecutionServiceConfigTest.java`
- Modify: `src/main/java/org/acme/service/JobExecutionService.java`

- [x] **Step 1: failing test로 `soft[0]`, `soft[1]` 저장 매핑 고정**

`JobExecutionServiceConfigTest.extractScoreFieldsMapsBusinessSoftScoresAfterNightPriorityLevels`에 assertion을 추가한다.

```java
assertEquals(-7, fields.get("night48RestSoftScore"));
assertEquals(-30, fields.get("night32RestSoftScore"));
```

최종 테스트 본문은 다음 의미를 가져야 한다.

```java
BendableScore score = BendableScore.of(
        new int[] { 0 },
        new int[] { -7, -30, -120, -5400, 240 });

Map<String, Object> fields = JobExecutionService.extractScoreFields(score);

assertEquals(0, fields.get("hardScore"));
assertEquals(-7, fields.get("night48RestSoftScore"));
assertEquals(-30, fields.get("night32RestSoftScore"));
assertEquals(-120, fields.get("undesiredSoftScore"));
assertEquals(-5400, fields.get("fairSoftScore"));
assertEquals(240, fields.get("desiredSoftScore"));
```

- [x] **Step 2: focused service test 실패 확인**

Run:

```bash
./mvnw test -Dtest=JobExecutionServiceConfigTest
```

Expected before implementation: FAIL. `night48RestSoftScore`, `night32RestSoftScore`가 `null`로 조회된다.

- [x] **Step 3: `JobExecution` 필드 추가**

`JobExecution`의 score 필드 영역에 다음 필드를 추가한다.

```java
private Integer night48RestSoftScore;
private Integer night32RestSoftScore;
```

`getSoftScore()`와 `getUndesiredSoftScore()` 사이에 다음 getter/setter를 추가한다.

```java
public Integer getNight48RestSoftScore() {
    return night48RestSoftScore;
}

public void setNight48RestSoftScore(Integer night48RestSoftScore) {
    this.night48RestSoftScore = night48RestSoftScore;
}

public Integer getNight32RestSoftScore() {
    return night32RestSoftScore;
}

public void setNight32RestSoftScore(Integer night32RestSoftScore) {
    this.night32RestSoftScore = night32RestSoftScore;
}
```

- [x] **Step 4: `extractScoreFields` 매핑 추가**

`JobExecutionService.extractScoreFields(BendableScore score)`를 다음처럼 확장한다.

```java
static Map<String, Object> extractScoreFields(BendableScore score) {
    Map<String, Object> scoreFields = new HashMap<>();
    scoreFields.put("hardScore", score.hardScore(0));
    scoreFields.put("night48RestSoftScore", score.softScore(0));
    scoreFields.put("night32RestSoftScore", score.softScore(1));
    scoreFields.put("undesiredSoftScore", score.softScore(2));
    scoreFields.put("fairSoftScore", score.softScore(3));
    scoreFields.put("desiredSoftScore", score.softScore(4));
    return scoreFields;
}
```

- [x] **Step 5: focused service test 통과 확인**

Run:

```bash
./mvnw test -Dtest=JobExecutionServiceConfigTest
```

Expected after implementation: PASS.

- [x] **Step 6: Task 2 커밋**

```bash
git add src/main/java/org/acme/model/JobExecution.java src/main/java/org/acme/service/JobExecutionService.java src/test/java/org/acme/service/JobExecutionServiceConfigTest.java
git commit -m "feat: persist night rest soft score fields"
```

## Task 3: Status API 응답에 night-rest score 노출

**Files:**
- Modify: `src/main/java/org/acme/api/dto/StatusResponse.java`
- Test: `src/test/java/org/acme/api/dto/StatusResponseTest.java`

- [x] **Step 1: failing test로 bendable 응답 필드 고정**

`StatusResponseTest.testFrom_MapsBendableScoreFields`에서 job setup에 신규 score를 추가한다.

```java
job.setNight48RestSoftScore(-7);
job.setNight32RestSoftScore(-30);
```

assertion을 추가한다.

```java
Assertions.assertEquals(-7, response.score().night48RestSoftScore());
Assertions.assertEquals(-30, response.score().night32RestSoftScore());
```

- [x] **Step 2: failing test로 legacy 응답 호환성 고정**

`StatusResponseTest.testFrom_MapsLegacySoftScore`에 다음 assertion을 추가한다.

```java
Assertions.assertNull(response.score().night48RestSoftScore());
Assertions.assertNull(response.score().night32RestSoftScore());
```

- [x] **Step 3: failing test로 night-only bendable 판별 고정**

`StatusResponseTest`에 다음 테스트를 추가한다.

```java
@Test
void testFrom_TreatsNightRestOnlyScoresAsBendableScoreFields() {
    JobExecution job = new JobExecution();
    job.setId("test-id");
    job.setStatus(ExecutionStatus.COMPLETED);
    job.setHardScore(0);
    job.setNight48RestSoftScore(-7);

    StatusResponse response = StatusResponse.from(job, objectMapper);

    Assertions.assertNotNull(response.score());
    Assertions.assertEquals(0, response.score().hardScore());
    Assertions.assertEquals(-7, response.score().night48RestSoftScore());
    Assertions.assertNull(response.score().night32RestSoftScore());
    Assertions.assertNull(response.score().undesiredSoftScore());
    Assertions.assertNull(response.score().fairSoftScore());
    Assertions.assertNull(response.score().desiredSoftScore());
    Assertions.assertNull(response.score().legacySoftScoreTotal());
}
```

- [x] **Step 4: focused DTO test 실패 확인**

Run:

```bash
./mvnw test -Dtest=StatusResponseTest
```

Expected before implementation: 컴파일 실패. `ScoreInfo`에 `night48RestSoftScore()`, `night32RestSoftScore()` accessor가 없다.

- [x] **Step 5: `ScoreInfo` record 필드 추가**

`StatusResponse.ScoreInfo`를 다음 순서로 확장한다.

```java
public record ScoreInfo(
        @JsonProperty("hard_score") Integer hardScore,
        @JsonProperty("night48_rest_soft_score") Integer night48RestSoftScore,
        @JsonProperty("night32_rest_soft_score") Integer night32RestSoftScore,
        @JsonProperty("undesired_soft_score") Integer undesiredSoftScore,
        @JsonProperty("fair_soft_score") Integer fairSoftScore,
        @JsonProperty("desired_soft_score") Integer desiredSoftScore,
        @JsonProperty("legacy_soft_score_total") Integer legacySoftScoreTotal) {
}
```

- [x] **Step 6: bendable branch 생성자 인자 추가**

`StatusResponse.from`의 bendable score branch를 다음처럼 바꾼다.

```java
scoreInfo = new ScoreInfo(
        job.getHardScore(),
        job.getNight48RestSoftScore(),
        job.getNight32RestSoftScore(),
        job.getUndesiredSoftScore(),
        job.getFairSoftScore(),
        job.getDesiredSoftScore(),
        null);
```

- [x] **Step 7: legacy branch 생성자 인자 추가**

legacy branch를 다음처럼 바꾼다.

```java
scoreInfo = new ScoreInfo(
        job.getHardScore(),
        null,
        null,
        null,
        null,
        null,
        job.getSoftScore());
```

- [x] **Step 8: `hasBendableSoftScores` 판별 확장**

`hasBendableSoftScores`를 다음처럼 바꾼다.

```java
private static boolean hasBendableSoftScores(JobExecution job) {
    return job.getNight48RestSoftScore() != null
            || job.getNight32RestSoftScore() != null
            || job.getUndesiredSoftScore() != null
            || job.getFairSoftScore() != null
            || job.getDesiredSoftScore() != null;
}
```

- [x] **Step 9: focused DTO test 통과 확인**

Run:

```bash
./mvnw test -Dtest=StatusResponseTest
```

Expected after implementation: PASS.

- [x] **Step 10: JSON 직렬화 smoke test 추가 여부 판단**

현재 `StatusResponseTest`는 record accessor 중심이다. JSON field name 회귀 위험을 낮추려면 다음 테스트를 추가한다.

```java
@Test
void testFrom_SerializesNightRestScoreJsonNames() throws Exception {
    JobExecution job = new JobExecution();
    job.setId("test-id");
    job.setStatus(ExecutionStatus.COMPLETED);
    job.setHardScore(0);
    job.setNight48RestSoftScore(-7);
    job.setNight32RestSoftScore(-30);

    StatusResponse response = StatusResponse.from(job, objectMapper);
    String json = objectMapper.writeValueAsString(response);

    Assertions.assertTrue(json.contains("\"night48_rest_soft_score\":-7"));
    Assertions.assertTrue(json.contains("\"night32_rest_soft_score\":-30"));
}
```

이 테스트를 추가하면 다시 실행한다.

```bash
./mvnw test -Dtest=StatusResponseTest
```

Expected after optional test: PASS.

- [x] **Step 11: Task 3 커밋**

```bash
git add src/main/java/org/acme/api/dto/StatusResponse.java src/test/java/org/acme/api/dto/StatusResponseTest.java
git commit -m "feat: expose night rest soft scores in status response"
```

## Task 4: API 문서 score contract 갱신

**Files:**
- Modify: `docs/API_DOCUMENTATION.md`
- Optional Modify: `docs/WORK_SUMMARY.md`

- [x] **Step 1: `GET /api/status/{id}` 응답 예시 수정**

`docs/API_DOCUMENTATION.md`의 status 응답 예시 score 블록을 다음 형태로 바꾼다.

```json
"score": {
  "hard_score": 0,
  "night48_rest_soft_score": -7,
  "night32_rest_soft_score": -30,
  "undesired_soft_score": -120,
  "fair_soft_score": -5400,
  "desired_soft_score": 240,
  "legacy_soft_score_total": null
}
```

- [x] **Step 2: score 설명 수정**

기존 `undesired_soft_score`가 Soft 1순위라고 설명된 부분을 다음으로 바꾼다.

```text
night48_rest_soft_score: Soft 1순위, 2연속 Night 후 다음 근무까지 48시간 휴식 부족분
night32_rest_soft_score: Soft 2순위, Night 후 다음 Day shift까지 32시간 휴식 부족분
undesired_soft_score: Soft 3순위, Off/비선호 요청일 배정 페널티
fair_soft_score: Soft 4순위, 근무 유형별 분배 균형
desired_soft_score: Soft 5순위, 선호일 배정 보상
legacy_soft_score_total: 구버전 문서 호환용 단일 soft 점수
```

- [x] **Step 3: TypeScript `StatusScoreV2` 타입 수정**

`StatusScoreV2`를 다음 형태로 바꾼다.

```ts
type StatusScoreV2 = {
  hard_score: number | null;
  night48_rest_soft_score: number | null;
  night32_rest_soft_score: number | null;
  undesired_soft_score: number | null;
  fair_soft_score: number | null;
  desired_soft_score: number | null;
  legacy_soft_score_total: number | null;
};
```

- [x] **Step 4: TypeScript parsed score 타입 수정**

`ParsedStatusScore`에 신규 필드를 추가한다.

```ts
type ParsedStatusScore = {
  hard: number | null;
  night48Rest: number | null;
  night32Rest: number | null;
  undesired: number | null;
  fair: number | null;
  desired: number | null;
  legacyTotal: number | null;
};
```

- [x] **Step 5: TypeScript parser의 V2 판별과 반환값 수정**

V2 판별 조건에 신규 필드를 추가한다.

```ts
if (
  score &&
  ("night48_rest_soft_score" in score ||
    "night32_rest_soft_score" in score ||
    "undesired_soft_score" in score ||
    "fair_soft_score" in score ||
    "desired_soft_score" in score ||
    "legacy_soft_score_total" in score)
) {
  return {
    hard: score.hard_score ?? null,
    night48Rest: score.night48_rest_soft_score ?? null,
    night32Rest: score.night32_rest_soft_score ?? null,
    undesired: score.undesired_soft_score ?? null,
    fair: score.fair_soft_score ?? null,
    desired: score.desired_soft_score ?? null,
    legacyTotal: score.legacy_soft_score_total ?? null,
  };
}
```

legacy branch에는 신규 parsed 필드를 `null`로 둔다.

```ts
return {
  hard: score.hard_score ?? null,
  night48Rest: null,
  night32Rest: null,
  undesired: null,
  fair: null,
  desired: null,
  legacyTotal: score.soft_score ?? null,
};
```

- [x] **Step 6: `docs/WORK_SUMMARY.md` 수정 여부 판단**

다음 기준으로 결정한다.

```text
현재 프론트엔드 연동자가 참고하는 운영 문서라면 수정한다.
이미 완료된 과거 작업 기록이라면 수정하지 않는다.
```

수정한다면 `docs/API_DOCUMENTATION.md`와 동일한 score 예시, 필드 설명, TypeScript 타입/parser를 반영한다.

- [x] **Step 7: 문서 diff 확인**

Run:

```bash
git diff -- docs/API_DOCUMENTATION.md docs/WORK_SUMMARY.md
```

Expected: status API score contract에 신규 night-rest 필드가 추가되고, 기존 legacy 호환 설명은 유지된다.

- [x] **Step 8: Task 4 커밋**

```bash
git add docs/API_DOCUMENTATION.md
git add docs/WORK_SUMMARY.md
git commit -m "docs: document night rest soft score fields"
```

`docs/WORK_SUMMARY.md`를 수정하지 않았다면 두 번째 `git add`는 생략한다.

## Task 5: 통합 검증

**Files:**
- Verify only: implementation and docs touched by Tasks 1-4

- [x] **Step 1: focused regression 실행**

Run:

```bash
./mvnw test -Dtest=NightHardConstraintValidatorTest,JobExecutionServiceConfigTest,StatusResponseTest
```

Expected: PASS.

Result: PASS. `Tests run: 20, Failures: 0, Errors: 0, Skipped: 0`.

- [x] **Step 2: full regression 실행**

Run:

```bash
./mvnw test
```

Expected: PASS.

Result: sandbox 환경에서는 Quarkus HTTP 서버 바인드가 `java.net.SocketException: Operation not permitted`로 실패했다. sandbox에서 실행 가능한 비-`@QuarkusTest` 회귀는 별도 실행했고 `Tests run: 77, Failures: 0, Errors: 0, Skipped: 0`로 통과했다. 비-sandbox 전체 테스트 재실행은 정책상 승인되지 않았다.

- [x] **Step 3: 최종 diff 검토**

Run:

```bash
git diff -- src/main/java/org/acme/solver/validation/NightHardConstraintValidator.java src/main/java/org/acme/model/JobExecution.java src/main/java/org/acme/service/JobExecutionService.java src/main/java/org/acme/api/dto/StatusResponse.java src/test/java/org/acme/solver/validation/NightHardConstraintValidatorTest.java src/test/java/org/acme/service/JobExecutionServiceConfigTest.java src/test/java/org/acme/api/dto/StatusResponseTest.java docs/API_DOCUMENTATION.md docs/WORK_SUMMARY.md
```

Confirm:

```text
32h/48h 휴식 부족은 ValidationException을 발생시키지 않는다.
soft[0]은 night48RestSoftScore / night48_rest_soft_score로 노출된다.
soft[1]은 night32RestSoftScore / night32_rest_soft_score로 노출된다.
기존 business soft score index는 soft[2], soft[3], soft[4]로 유지된다.
legacy score 응답은 기존 문서와 호환된다.
```

- [x] **Step 4: 최종 커밋 상태 확인**

Run:

```bash
git status --short
```

Expected: 작업 커밋을 모두 만들었다면 clean. 계획 문서 자체를 같은 브랜치에서 추적한다면 `docs/superpowers/plans/2026-05-10-night-rest-score-observability.md`도 커밋되어 있어야 한다.

Result: 최종 리뷰 시점 기준 working tree는 clean이며, 계획 문서도 브랜치에 커밋했다.

## Assumptions

- 32h/48h 야간 휴식은 최종 실패 조건이 아니라 강한 선호 조건이다.
- `EmployeeSchedulingConstraintProvider`의 현재 score index 정책은 이미 맞다: `soft[0]=48h`, `soft[1]=32h`, `soft[2]=undesired`, `soft[3]=fair`, `soft[4]=desired`.
- Firestore 문서는 schema-less이므로 별도 migration 없이 신규 저장부터 새 필드가 추가된다.
- 기존 Firestore 문서에 신규 필드가 없으면 API 응답의 `night48_rest_soft_score`, `night32_rest_soft_score`는 `null`이다.
- 신규 API 필드는 기존 필드를 제거하지 않는 additive change다.

## Plan Review Notes

- writing-plans 기준으로 파일 책임, exact paths, TDD 단계, expected output, 커밋 단위를 명시했다.
- 이 세션에서는 사용자가 subagent 실행을 명시적으로 요청하지 않았으므로 plan-document-reviewer subagent는 dispatch하지 않았다. 실행 전 별도 리뷰 루프를 돌릴 경우 `@superpowers:writing-plans`의 Plan Review Loop 절차에 따라 이 계획 문서 경로를 reviewer에게 제공한다.
