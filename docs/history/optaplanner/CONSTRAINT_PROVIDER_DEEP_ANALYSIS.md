# EmployeeSchedulingConstraintProvider 심층 분석

> 분석 대상: `org.acme.solver.algorithm.EmployeeSchedulingConstraintProvider`  
> 관련 클래스: `ShiftDateMatcher`, `AvailabilityValidator`, `UndesirablePhaseProcessor`  
> 점수 체계: `BendableScore` (1 Hard / 6 Soft)

---

## 1. 개요

이 클래스는 **OptaPlanner 기반 직원 스케줄링의 핵심 제약 조건 엔진**으로, `ConstraintProvider` 인터페이스를 구현합니다. 스케줄의 유효성(Hard Constraint)과 품질(Soft Constraint)을 수학적으로 평가하며, 특히 간호사 스케줄링에서 필수적인 **연속 근무 제한**, **휴식 시간 보장**, **근무 공정성**, **개인 선호도 반영**을 모델링합니다.

본 문서는 클래스의 전체 구조를 설명하고, **핵심 이슈인 `undesiredDayForEmployee`의 날짜 매칭 정책**을 코드, 실무 관행, 프레임워크 표준의 세 가지 관점에서 심층 분석합니다.

---

## 2. 점수 체계: BendableScore (1 Hard / 6 Soft)

| 레벨 | 인덱스 | 이름 | 의미 | 가중치 상수 |
|---|---|---|---|---|
| **Hard** | 0 | `HARD_LEVEL_INDEX` | 절대 위반 불가 | `ONE_HARD` |
| **Soft 0** | 0 | `SOFT_NIGHT_48H_REST` | 2연속 야근 후 48시간 휴식 | `ONE_SOFT_NIGHT_48H_REST` |
| **Soft 1** | 1 | `SOFT_NIGHT_32H_REST` | 야근 후 다음 낮근무까지 32시간 휴식 | `ONE_SOFT_NIGHT_32H_REST` |
| **Soft 2** | 2 | `SOFT_UNDESIRED` | 비희망 근무 배정 최소화 | `ONE_SOFT_UNDESIRED` |
| **Soft 3** | 3 | `SOFT_THREE_CONSECUTIVE_NIGHT` | 3연속 야근 최소화 | `ONE_SOFT_THREE_CONSECUTIVE_NIGHT` |
| **Soft 4** | 4 | `SOFT_FAIR` | 근무 유형별 공정한 분배 | `ONE_SOFT_FAIR` |
| **Soft 5** | 5 | `SOFT_DESIRED` | 희망 근무 배정 극대화 | `ONE_SOFT_DESIRED` |

**우선순위 해석**: `night48` > `night32` > `undesired` > `3-consecutive-night` > `fair` > `desired`.  
상위 soft 제약을 조금이라도 양보하지 않고 하위 soft 제약을 만족시키는 방향으로 최적화됩니다.

---

## 3. 제약조건 목록

### 3.1 하드 제약조건 (7개)

| 제약명 | 메서드 | 핵심 로직 |
|---|---|---|
| **Missing required skill** | `requiredSkill()` | 직원의 `skillSet`에 `requiredSkill`이 없으면 penalty |
| **Overlapping shift** | `noOverlappingShifts()` | 같은 직원에게 시간이 겹치는 두 근무가 배정되면 penalty (겹치는 분만큼) |
| **At least 12 hours between 2 shifts** | `atLeast12HoursBetweenTwoShifts()` | 두 근무 간 휴식이 12시간 미만이면 부족한 분만큼 penalty |
| **No four consecutive night shifts** | `noFourConsecutiveNightShifts()` | 논리일 기준 4연속 야근(N)이 배정되면 penalty |
| **Max 15 night shifts per month** | `max15NightShiftsPerMonth()` | 월간 야근 횟수가 15회를 초과하면 초과분만큼 penalty |
| **Max one shift per day** | `oneShiftPerDay()` | 같은 직원에게 하루에 2개 이상의 근무가 배정되면 penalty |
| **Unavailable employee** | `unavailableEmployee()` | `UNAVAILABLE`로 표시된 날짜에 근무 배정 시 근무 시간(분)만큼 penalty |

### 3.2 소프트 제약조건 (6개)

| 제약명 | 메서드 | 우선순위 | 핵심 로직 |
|---|---|---|---|
| **At least 48h after two consecutive night shifts** | `atLeast48HoursAfterTwoConsecutiveNightShifts()` | 최상 (0) | 2연속 야근 후 다음 근무까지 48시간 미만이면 부족한 분만큼 penalty |
| **At least 32h from night to next day shift** | `atLeast32HoursFromNightToNextDayShift()` | 2번째 (1) | 야근(N) 후 다음 낮근무(D)까지 32시간 미만이면 penalty |
| **Undesired day for employee** | `undesiredDayForEmployee()` | 3번째 (2) | `UNDESIRED`로 표시된 날짜(논리일/실제일 모두 매칭)에 근무 배정 시 근무 시간만큼 penalty. **pinned 근무는 제외** |
| **Minimize three consecutive night shifts** | `minimizeThreeConsecutiveNightShifts()` | 4번째 (3) | 3연속 야근 발생 시 penalty (2연속은 허용, 3연속은 비선호) |
| **Fair shift distribution** | `fairShiftDistribution()` | 5번째 (4) | 직원별/근무유형별 근무 횟수의 제곱합을 최소화. Night(가중치10), Evening(5), Day(1) |
| **Desired day for employee** | `desiredDayForEmployee()` | 최하 (5) | `DESIRED`로 표시된 날에 근무 배정 시 근무 시간만큼 reward |

---

## 4. 핵심 도메인 정책: Night 근무의 논리일 (Logical Date)

`ShiftDateMatcher`는 Night 근무의 날짜 귀속을 다음과 같이 정의합니다.

```java
public static LocalDate resolveLogicalDate(Shift shift) {
    LocalDate actualStartDate = shift.getStart().toLocalDate();
    if (!isNightShift(shift)) {
        return actualStartDate;
    }
    LocalTime startTime = shift.getStart().toLocalTime();
    return startTime.isBefore(NIGHT_LOGICAL_DAY_CUTOFF) // 06:00 기준
            ? actualStartDate.minusDays(1) // 06:00 이전 시작이면 전날
            : actualStartDate;
}
```

**정책 요약**:
- **Day/Evening 근무**: 실제 시작일 = 논리일
- **Night 근무**:  
  - 시작 시각이 **06:00 이전**이면 → **실제 시작일의 전날**을 논리일로 사용  
    (예: 월요일 00:00~08:00 N → 논리일: **일요일**)
  - 시작 시각이 **06:00 이후**이면 → 실제 시작일 그대로  
    (예: 월요일 23:00~화요일 07:00 N → 논리일: **월요일**)

**목적**: 날짜가 넘어가는 야근 근무를 인접한 날의 근무로 올바르게 처리하여, "연속 야근" 판단의 모호성을 제거합니다.

---

## 5. 이슈 분석: `undesiredDayForEmployee` 날짜 매칭 정책

### 5.1 현재 코드의 정확한 동작

`undesiredDayForEmployee`는 다음과 같은 복합 매칭 로직을 사용합니다.

```java
.filter((shift, availability) -> availability
        .getAvailabilityType() == AvailabilityType.UNDESIRED)
.filter((shift, availability) -> ShiftDateMatcher
        .matchesActualOrLogicalDate(shift, availability.getDate()))
```

`matchesActualOrLogicalDate`의 내부:
```java
public static boolean matchesActualOrLogicalDate(Shift shift, LocalDate targetDate) {
    return overlapsActualDate(shift, targetDate) || targetDate.equals(resolveLogicalDate(shift));
}
```

**즉, `UNDESIRED` penalty는 두 조건 중 하나라도 만족하면 발생합니다:**
1. **실제 날짜 오버랩**: 근무 시간이 해당 날짜의 00:00~23:59와 겹치는 경우
2. **논리일 매칭**: 근무의 논리일(또는 실제 시작일)이 해당 날짜와 일치하는 경우

### 5.2 테스트 케이스 기반 검증

`EmployeeSchedulingConstraintProviderTest`의 테스트 결과:

| 테스트명 | Availability 날짜 | Shift | 결과 |
|---|---|---|---|
| `MatchesNightLogicalDate` | **1월 5일(일)** | 1월 6일 00:00~08:00(N) | **penalty 480** |
| `MatchesNightActualDate` | **1월 6일(월)** | 1월 6일 00:00~08:00(N) | **penalty 480** |
| `NightBeforeCutoff_UsesPreviousLogicalDate` | **1월 5일(일)** | 1월 6일 05:59~13:59(N) | **penalty 480** |
| `NightAtCutoff_DoesNotUsePreviousLogicalDate` | **1월 5일(일)** | 1월 6일 06:00~14:00(N) | **penalty 0** |
| `AppliesLogicalAndActualDatesOncePerShift` | 1월 5일(일) + 1월 6일(월) | 1월 6일 00:00~08:00(N) | **penalty 480 (중복 없음)** |

**핵심 결론**:
- 월요일을 `UNDESIRED`로 등록하면, **월요일 00:00~08:00 N 근무(실제일 오버랩)**와 **일요일 23:00~월요일 07:00 N 근무(논리일 매칭)** 모두 penalty를 받습니다.
- 즉, 현재 코드는 **"둘 다 현실적으로 불가능하게 처리"**합니다.
- 중복 등록(논리일+실제일)에 대해서는 `.groupBy((shift, availability) -> shift)`로 **한 번만 penalty**가 부과됩니다.

### 5.3 실제 간호사 스케줄링 관행

#### 업계 표준: "Shift Day = 시작일"

| 출처 | 핵심 내용 |
|---|---|
| **SAP SuccessFactors** | "The shift day is always the day on which the night shift starts and typically all hours recorded shall count for that day." |
| **Kronos/UKG** | "Whole day off" 요청 시, 선택한 날짜에 **시작 시간이 있는 shift**만 오버라이드 |
| **Kindred Hospitals** | "Weekends are defined as... Friday/Saturday/Sunday night shifts (1900-0730)" → Night는 시작일 기준 |
| **Timefold 공식 문서** | "By default, period rules count shifts... if the shift starts within the defined period." |

**업계 표준은 명확합니다**: 일요일 23:00~월요일 07:00 Night 근무는 **"일요일 근무"**로 간주됩니다. 따라서 월요일 Off 요청은 이 근무를 차단하지 않아야 합니다.

#### 그러나 노조 규약은 더 엄격할 수 있음

**UNA(캐나다 간호사 노조) Scheduling Rules**:
> "for Employees working night Shifts, at no time shall an Employee be scheduled to work more than one hour on a day considered to be a scheduled day of rest."

이 규약에 따르면, 월요일이 "day of rest"로 지정된 경우, 월요일에 **1시간 초과 근무는 절대 금지**됩니다. 일요일 23:00~월요일 07:00 근무는 월요일 00:00~07:00에 7시간을 근무하는 것이므로, **이 규약 하에서는 월요일 Off와 충돌**합니다.

### 5.4 OptaPlanner/Timefold 표준과의 비교

| 시스템 | `UNDESIRED` 매칭 방식 | Night 근무 날짜 귀속 |
|---|---|---|
| **Timefold Quickstart (공식)** | `shift.start.toLocalDate()` | 시작일 |
| **OptaWeb Employee Rostering** | `doTimeslotsIntersect()` (시간 교차) | 시작일 |
| **INRC 2010 Nurse Rostering** | `shiftDate` 직접 비교 | 날짜 자체가 Shift의 속성 |
| **현재 코드 (every-shift)** | `matchesActualOrLogicalDate()` (복합) | **논리일 정책 적용** |

**현재 코드는 업계 표준과 다르게 "논리일" 개념을 도입**했습니다. 이는 업계 표준의 "시작일 기준"보다 **간호사의 실제 피로도와 회복 주기**를 더 세밀하게 반영하려는 의도로 보입니다.

### 5.5 질문의 3가지 근무 유형별 현실적 판단

| 근무 유형 | 업계 표준(시작일 기준) | UNA 노조 규약 | 현재 코드 동작 |
|---|---|---|---|
| **월요일 09:00~17:00 (D)** | ❌ 월요일 근무 → 금지 | ❌ 금지 | ❌ penalty |
| **일요일 23:00~월요일 07:00 (N)** | ✅ **가능** (일요일 근무) | ❌ 불가능 (월요일 7시간 근무) | ❌ penalty (논리일+오버랩) |
| **월요일 00:00~08:00 (N)** | ❌ 월요일 근무 → 금지 | ❌ 금지 | ❌ penalty (시작일+오버랩) |

**"월요일 Off 시 일요일 Night 근무가 현실적으로 가능한가?"**  
→ 업계 표준상 **가능**하지만, 노조 규약과 현재 코드상 **불가능**합니다.

---

## 6. 현재 설계의 장단점

### 6.1 장점

1. **간호사의 실제 피로도 반영**:  
   일요일 23:00~월요일 07:00 근무 후 월요일 오후 가족 행사에 참석하기는 현실적으로 어렵습니다. 현재 코드는 "월요일 Off"가 이런 상황까지 보호합니다.

2. **Night 근무 후 휴식과의 연계**:  
   "야근 후 다음 날 휴식"이라는 직관을 코드에 반영했습니다.

3. **중복 penalty 방지**:  
   `.groupBy((shift, availability) -> shift)`로 논리일과 실제일이 동시에 매칭되더라도 한 번만 penalty가 부과됩니다.

### 6.2 단점

1. **UNAVAILABLE(하드)과의 정책 불일치** (🔴 치명적):  
   `AvailabilityValidator`는 `UNAVAILABLE`을 검증할 때 **시작일만** 확인합니다.
   ```java
   // AvailabilityValidator.java (line 44)
   LocalDate shiftDate = shift.getStart().toLocalDate(); // 시작일만!
   ```
   즉, `UNDESIRED`(소프트)는 논리일까지 검증하는데, `UNAVAILABLE`(하드)은 시작일만 검증합니다.  
   **"절대 불가능"으로 표시된 날짜가 "비선호"보다 덜 엄격하게 검증되는 모순**입니다.

2. **사용자 인식과의 괴리**:  
   간호사가 앱에서 "월요일 Off"를 선택했을 때, "일요일 밤 11시 야근도 안 돼?"라고 느낄 수 있습니다.

3. **업계 표준과의 괴리**:  
   Kronos, SAP, Timefold 등 주요 시스템과의 연동 시 정책 충돌 가능성이 있습니다.

---

## 7. 개선 제안

### 제안 A: 정책 문서화 및 사용자 안내 (즉시 가능, 낮은 리스크)

현재의 복합 매칭 로직을 유지하되, 사용자(간호사)에게 명확히 안내합니다.

> "Night(야간) 근무는 근무 종료일까지 Off 요청에 포함됩니다. 예: 월요일 Off 요청 시, 일요일 밤~월요일 새벽 야근도 배정되지 않습니다."

**구현**: UI/알림 메시지 추가. 코드 변경 없음.

### 제안 B: UNAVAILABLE과 정책 통일 (필수 버그 수정)

`AvailabilityValidator`를 `ShiftDateMatcher` 기준으로 수정하여, Hard와 Soft 제약의 검증 정책을 일치시킵니다.

```java
// 현재 (잘못됨)
LocalDate shiftDate = shift.getStart().toLocalDate();

// 개선안
LocalDate logicalDate = ShiftDateMatcher.resolveLogicalDate(shift);
boolean overlapsActual = ShiftDateMatcher.overlapsActualDate(shift, availability.getDate());
if (availabilityType == AvailabilityType.UNAVAILABLE && 
    (overlapsActual || logicalDate.equals(availability.getDate()))) {
    throw new ValidationException(...);
}
```

### 제안 C: 설정 가능한 매칭 정책 (장기 개선)

조직별로 정책이 다르므로, `application.properties`에서 선택 가능하게 합니다.

```properties
# OFF 요청 매칭 정책
shift.off-request.match-policy=LOGICAL_AND_ACTUAL
# 옵션: START_DATE_ONLY, ACTUAL_OVERLAP, LOGICAL_DATE, LOGICAL_AND_ACTUAL
```

| 정책 | 설명 | 적합한 조직 |
|---|---|---|
| `START_DATE_ONLY` | 업계 표준. Night 근무는 시작일 기준 | Kronos/SAP 연동 조직, 대형 병원 |
| `ACTUAL_OVERLAP` | 근무 시간이 해당 날짜와 1분이라도 겹치면 매칭 | 엄격한 노조 규약 환경 |
| `LOGICAL_DATE` | Night 근무만 논리일 기준 | 현재 코드의 절반만 적용 |
| `LOGICAL_AND_ACTUAL` | 현재 코드 그대로 | "회복 시간"을 중시하는 조직 |

---

## 8. 결론

현재 `EmployeeSchedulingConstraintProvider`의 `undesiredDayForEmployee` 설계는 **"간호사의 실제 삶을 반영한 합리적인 선택"**일 수 있습니다. 특히 Night 근무 후의 회복 시간을 고려하여, 종료일까지 `UNDESIRED` 매칭 범위에 포함시킨 것은 운영 환경의 엣지 케이스를 잘 반영하고 있습니다.

그러나 **(1) `UNAVAILABLE` 검증과의 불일치**와 **(2) 업계 표준과의 괴리**라는 두 가지 명확한 문제를 안고 있습니다. 특히 `AvailabilityValidator`의 시작일 기준 검증은 의도치 않게 Hard 제약을 Soft보다 약하게 만드는 버그로 판단됩니다.

**권장하는 방향**은 다음과 같습니다:
1. **즉시**: `AvailabilityValidator`를 `ShiftDateMatcher`와 동일한 논리일 기준으로 수정 (버그 수정)
2. **단기**: 사용자 UI에 "Night 근무는 종료일까지 Off에 포함됩니다" 안내 문구 추가
3. **장기**: 조직별 정책 설정을 위한 `match-policy` 프로퍼티 도입 검토
