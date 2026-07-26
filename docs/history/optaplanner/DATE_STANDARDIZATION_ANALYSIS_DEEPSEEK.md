# DATE_STANDARDIZATION_ANALYSIS.md 비판적 검토 보고서

> 분석 일자: 2026-05-22  
> 검토 대상: `docs/DATE_STANDARDIZATION_ANALYSIS.md` (이하 "분석 문서")  
> 검증 범위: 프로덕션 코드 14개 파일, 테스트 코드 16개 파일 전수 확인  
> 검토자: DeepSeek

---

## 목차

1. [검증 결과 개요](#1-검증-결과-개요)
2. [사실 오류: atLeast32HoursFromNightToNextDayShift의 논리일 분류](#2-사실-오류-atleast32hoursfromnighttonextdayshift의-논리일-분류)
3. [사실 오류: atLeast48HoursAfterTwoConsecutiveNightShifts의 분류 정밀도](#3-사실-오류-atleast48hoursaftertwoconsecutivenightshifts의-분류-정밀도)
4. [미해결 공백: undesiredDayForEmployee 변경의 트레이드오프](#4-미해결-공백-undesireddayforemployee-변경의-트레이드오프)
5. [분석 누락: ShiftDateMatcher 클래스의 운명](#5-분석-누락-shiftdatematcher-클래스의-운명)
6. [분석 누락: overlapsActualDate의 사생활](#6-분석-누락-overlapsactualdate의-사생활)
7. [근거 부족: "업계 표준" 주장](#7-근거-부족-업계-표준-주장)
8. [검증된 정확한 주장들](#8-검증된-정확한-주장들)
9. [테스트 영향 분석](#9-테스트-영향-분석)
10. [종합 평가 및 권장 수정 사항](#10-종합-평가-및-권장-수정-사항)

---

## 1. 검증 결과 개요

| 항목 | 상태 | 비고 |
|---|---|---|
| 3가지 날짜 기준(A/B/C) 분류 | ✅ 정확함 | 전수 코드 검증 완료 |
| UNAVAILABLE 제거 및 AvailabilityValidator 죽은 코드 | ✅ 정확함 | enum에서 UNAVAILABLE 삭제됨 |
| UNDESIRED vs DESIRED 비대칭 | ✅ 정확함 | `matchesActualOrLogicalDate` vs `shift.getStart().toLocalDate()` |
| Export/통계 날짜 불일치 | ✅ 정확함 | `resolveLogicalDateForExport` vs `buildEmployeeShiftMap` |
| oneShiftPerDay 시작일 기준 유지 필요 | ✅ 타당함 | 논리일 기준 시 17시간 연속 근무 허용되는 버그 발생 |
| 월간 Night 횟수 시작월 기준 | ✅ 타당함 | 급여/근태 시스템 호환성 근거 명확 |
| **atLeast32Hours의 논리일 분류** | ❌ **사실 오류** | 실제 코드는 논리일 미사용 |
| **atLeast48Hours의 논리일 사용 범위** | ⚠️ **분류 부정확** | 연속 판별에만 논리일, 휴식 계산은 실제 시간 |
| **undesiredDayForEmployee 변경의 빈틈** | ⚠️ **방어 논리 불완전** | Night→Evening 시나리오 미커버 |
| **ShiftDateMatcher 정리 계획** | ❌ **분석 누락** | `matchesActualOrLogicalDate` 제거 시 `overlapsActualDate` 사망 |
| **"업계 표준" 주장** | ❌ **인용 부재** | 구체적 출처 없음 |
| **실행 순서** | ✅ 타당함 | 즉시→단기→검증→장기 phased approach 현실적 |

---

## 2. 사실 오류: atLeast32HoursFromNightToNextDayShift의 논리일 분류

### 분석 문서의 주장

분석 문서 §2 기준 B 표는 `atLeast32HoursFromNightToNextDayShift`가 **논리일 기준**이라고 분류합니다.

### 실제 코드

```java
// EmployeeSchedulingConstraintProvider.java:138-153
Constraint atLeast32HoursFromNightToNextDayShift(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Shift.class)
            .filter(EmployeeSchedulingConstraintProvider::isNightShift)          // [1] shiftCode == "N"
            .join(Shift.class,
                    Joiners.equal(Shift::getEmployee),
                    Joiners.lessThan(Shift::getEnd, Shift::getStart))           // [2] 실제 시간 비교
            .filter((nightShift, dayShift) -> isDayShift(dayShift))             // [3] shiftCode == "D"
            .groupBy((Shift nightShift, Shift dayShift) -> nightShift,
                    ConstraintCollectors.<Shift, Shift, LocalDateTime>min(
                            (Shift nightShift, Shift dayShift) -> dayShift.getStart()))
            .filter((nightShift, nextDayStart) -> getMinutesBetween(            // [4] 실제 시간 차이
                    nightShift.getEnd(), nextDayStart) < MIN_NIGHT_TO_NEXT_DAY_REST_MINUTES)
            .penalize(...)
}
```

**핵심**: 이 메서드는 `resolveLogicalDate()`, `getNightLogicalDate()`, `ShiftDateMatcher`를 **전혀 호출하지 않습니다**.

| 단계 | 사용 기준 | 상세 |
|---|---|---|
| Night 선별 | shiftCode 문자열 비교 | `isNightShift()` → `resolveShiftType()` → `shift.getShiftCode()` |
| Shift 쌍 매칭 | 실제 LocalDateTime | `Joiners.lessThan(Shift::getEnd, Shift::getStart)` |
| Day 선별 | shiftCode 문자열 비교 | `isDayShift()` → `resolveShiftType()` |
| 휴식 시간 계산 | 실제 LocalDateTime | `getMinutesBetween(nightShift.getEnd(), nextDayStart)` |

### 분류 기준

분석 문서의 기준 B(논리일)에 이 메서드를 포함시킨 유일한 근거는 "Night 근무를 다루므로"라는 암묵적 가정으로 보입니다. 하지만 Night 근무를 다룬다는 것과 논리일을 사용한다는 것은 별개의 문제입니다. Shift 코드로 Night을 식별하는 것과 ShiftDateMatcher의 `resolveLogicalDate`로 논리일을 계산하는 것은 완전히 다른 연산입니다.

### 영향

이 메서드는 §5의 수정 대상 요약에서 "유지"로 분류되어 있어 최종 결론에는 영향이 없습니다. 그러나 **분류표의 오류는 향후 유지보수 시 오해를 불러일으킬 위험**이 있습니다. 예를 들어 "논리일 기준인 제약들을 일괄 변경한다"는 결정이 내려질 때 이 제약이 부당하게 포함될 수 있습니다.

---

## 3. 사실 오류: atLeast48HoursAfterTwoConsecutiveNightShifts의 분류 정밀도

### 분석 문서의 주장

분석 문서 §2 기준 B는 이 메서드가 논리일 기준이라고 단일 분류합니다.

### 실제 코드

```java
// EmployeeSchedulingConstraintProvider.java:170-187
Constraint atLeast48HoursAfterTwoConsecutiveNightShifts(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Shift.class)
            .filter(EmployeeSchedulingConstraintProvider::isNightShift)
            .join(Shift.class, Joiners.equal(Shift::getEmployee))
            .filter((firstNight, secondNight) -> isNightShift(secondNight)
                    && getNightLogicalDate(secondNight).equals(                    // [1] 논리일: 연속 판별
                            getNightLogicalDate(firstNight).plusDays(1)))
            .join(Shift.class, ...)
            .filter((firstNight, secondNight, nextShift) ->
                    !nextShift.getStart().isBefore(secondNight.getEnd()))        // [2] 실제 시간: shift 순서
            .groupBy(...)
            .filter((secondNight, nextShiftStart) -> getMinutesBetween(           // [3] 실제 시간: 휴식 계산
                    secondNight.getEnd(), nextShiftStart) < MIN_REST)
            .penalize(...)
}
```

**실제로는 혼합 사용입니다**:
- **연속 Night 판별** (line 174-175): `getNightLogicalDate()` 사용 → **논리일**
- **이후 shift 선별** (line 178): `nextShift.getStart().isBefore(secondNight.getEnd())` → **실제 시간**
- **휴식 시간 계산** (line 182-183): `getMinutesBetween(secondNight.getEnd(), nextShiftStart)` → **실제 시간**

### 분류 개선 제안

이 메서드는 "논리일(부분)" 또는 "혼합"으로 분류하는 것이 정확합니다. 단순 "논리일" 분류는 이 제약의 복잡성과 실제 동작을 충분히 반영하지 못합니다.

---

## 4. 미해결 공백: undesiredDayForEmployee 변경의 트레이드오프

### 분석 문서의 방어 논리

분석 문서 §4 제안(2)는 `matchesActualOrLogicalDate`를 `shift.getStart().toLocalDate()`로 변경할 때 발생하는 빈틈(일요일 23:00 N이 월요일 UNDESIRED를 통과)이 다음 제약으로 커버된다고 주장합니다:

1. `atLeast32HoursFromNightToNextDayShift`
2. `atLeast48HoursAfterTwoConsecutiveNightShifts`
3. `noOverlappingShifts` (이미 물리적 중복 방지)

### 검증 결과: 완전히 커버되지 않는 시나리오 존재

다음 시나리오를 고려해보겠습니다. 직원이 **월요일을 UNDESIRED**로 등록했습니다.

| 시나리오 | 월요일 UNDESIRED로 차단? | 적용되는 제약 |
|---|---|---|
| 일요일 23:00~월요일 07:00 **N** | ~~변경 후 차단 안 됨~~ | `atLeast32HoursFromNightToNextDayShift`가 **Night→Day에만 적용**. Night→Night(E)는 적용 안 됨. `noOverlappingShifts`는 월요일 07:00에 끝나므로 중복 없음. |
| → **월요일 09:00~17:00 D** | | → **32h soft** (SOFT_NIGHT_32H_REST_INDEX=1 > SOFT_UNDESIRED_INDEX=2) → **의도대로 커버됨** |
| → **월요일 14:00~22:00 E** | | → **커버 안 됨**. 32h 제약은 Day shift에만 적용. |
| → **월요일 22:00~화요일 06:00 N** | | → **커버 안 됨**. 32h 제약은 Day shift에만. 48h 제약은 2연속 Night 필요. |

**취약 시나리오**: 일요일 N + 월요일 E (Night→Evening)
- `atLeast32Hours`: Day shift만 대상 → **미적용**
- `atLeast48Hours`: 2연속 Night가 아니므로 → **미적용**
- `noOverlappingShifts`: N은 07:00에 끝나고 E는 14:00에 시작 → **중복 없음**
- 결과: **아무 제약도 위반하지 않음**

### 그러나 이것이 반드시 문제인 것은 아니다

`UNDESIRED`의 소프트 레벨은 `SOFT_UNDESIRED_INDEX = 2`로, 전체 6단계 소프트 제약 중 중간 우선순위입니다. Hard가 아니므로 "가능하면 피하라"는 의도로 설계되었습니다. 따라서:

- **문서의 입장이 틀렸다**는 것은 아닙니다.
- 다만, **이 트레이드오프를 명시적으로 논의하지 않은 것**이 문제입니다.
- 위 시나리오가 현실에서 발생할 가능성과 심각성을 평가하고, 수용 가능한 트레이드오프인지 검토가 필요합니다.

### 개인적 판단

필자의 의견으로는, 이 트레이드오프는 **수용 가능**합니다. 이유는:
1. `UNDESIRED`는 Soft 제약이므로 절대 금지가 아님
2. `DESIRED`도 동일한 Soft 레벨(SOFT_DESIRED_INDEX=5)로 대칭을 맞추는 것이 일관성 측면에서 더 중요
3. Night→Evening 전환 시나리오는 현실에서 드묾
4. 문서 §4에서 "사용자 직관: 월요일 Off = 월요일에 시작하는 근무"라는 논리는 일관됨

---

## 5. 분석 누락: ShiftDateMatcher 클래스의 운명

분석 문서는 `undesiredDayForEmployee`의 변경을 제안하지만, 이 변경이 `ShiftDateMatcher` 클래스에 미치는 영향을 다루지 않습니다.

### 현황

```java
public final class ShiftDateMatcher {
    // 기준 C: 복합 매칭 → 제거 대상
    public static boolean matchesActualOrLogicalDate(Shift shift, LocalDate targetDate) {
        return overlapsActualDate(shift, targetDate) || targetDate.equals(resolveLogicalDate(shift));
    }

    // matchesActualOrLogicalDate에서만 호출 → 함께 사망
    public static boolean overlapsActualDate(Shift shift, LocalDate targetDate) {
        LocalDateTime dayStart = targetDate.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        return shift.getStart().isBefore(dayEnd) && shift.getEnd().isAfter(dayStart);
    }

    // 유일한 생존자
    public static LocalDate resolveLogicalDate(Shift shift) { ... }
}
```

### 제거 시 부수 효과

| 메서드 | 상태 | 호출자 | 조치 |
|---|---|---|---|
| `matchesActualOrLogicalDate` | 제거 대상 | ConstraintProvider, SolutionValidator, SolverRunnerTest | `undesiredDayForEmployee`를 Joiners.equal로 변경, SolutionValidator/SolverRunnerTest도 동기화 |
| `overlapsActualDate` | 자동 사망 | `matchesActualOrLogicalDate`에서만 호출 | 제거 |
| `resolveLogicalDate` | 생존 | 연속 Night 제약, JsonScheduleExporter, NightHardConstraintValidator | 유지 |

### 문제점

`ShiftDateMatcher`라는 클래스명은 날짜 매칭의 중립적 이름이지만, 단일 public 메서드(`resolveLogicalDate`)만 남게 되면 클래스의 존재 이유가 애매해집니다. 두 가지 선택지가 있습니다:

1. **클래스 유지**: `resolveLogicalDate`는 여전히 중요한 논리(06:00 컷오프 정책)를 캡슐화하고 있으므로 유지 가능
2. **메서드 이동**: `resolveLogicalDate`를 `NightShiftPolicy` 같은 더 명확한 이름의 클래스로 이동

문서는 이 선택을 논의해야 합니다. 개인적으로는 **선택지 1**이 합리적입니다. `ShiftDateMatcher`는 여전히 `resolveLogicalDate`라는 의미 있는 public 인터페이스를 제공하며, 향후 다른 날짜 관련 정책이 추가될 가능성도 있습니다.

---

## 6. 분석 누락: overlapsActualDate의 사생활

분석 문서는 `matchesActualOrLogicalDate`의 변경에 집중하지만, `overlapsActualDate` 메서드가 `matchesActualOrLogicalDate` **외부에서 사용되지 않는 점**을 언급하지 않습니다.

```
overlapsActualDate: 정의 (ShiftDateMatcher.java:25)
  └── 호출: matchesActualOrLogicalDate (ShiftDateMatcher.java:22)
       └── 호출자:
            ├── EmployeeSchedulingConstraintProvider.undesiredDayForEmployee (line 227)
            ├── SolutionValidator.logUndesiredSoftDiagnostics (line 103)
            └── SolverRunnerTest (line 78)
```

`overlapsActualDate`는 오직 `matchesActualOrLogicalDate`를 통해서만 간접 호출됩니다. 따라서 두 메서드 모두 제거 대상이며, 별도의 죽은 코드 정리가 필요하지 않습니다. 문서에 **"matchesActualOrLogicalDate 제거 시 overlapsActualDate도 함께 제거"** 라는 간단한 메모만 추가하면 됩니다.

---

## 7. 근거 부족: "업계 표준" 주장

### 분석 문서의 주장

분석 문서 §4 제안(2)에서:

> 업계 표준(Kronos, SAP, Timefold)과 일치

### 문제

1. **인용 부재**: 구체적인 문서 URL, 버전, 설정 가이드 라인이 없습니다.
2. **Timefold**: OptaPlanner의 후속 프로젝트로, Night 근무 날짜 귀속에 대한 명시적 가이드가 있는지 확인이 필요합니다.
3. **Kronos/SAP**: 각 제품이 Night 근무 귀속을 실제 시작일로 처리한다는 주장은 검증되지 않았습니다. 일부 시스템은 Night shift를 **이전 날짜**에 귀속시키는 옵션을 제공하기도 합니다.

### 권장

- 구체적인 출처를 찾아 추가하거나
- 출처를 찾을 수 없다면 "일반적인 관행" 또는 "다수의 스케줄링 시스템에서 사용하는 방식"으로 톤다운하는 것이 문서의 신뢰성에 좋습니다.

---

## 8. 검증된 정확한 주장들

분석 문서의 다음 주장들은 모두 실제 코드 검증을 통해 확인되었습니다.

### 8.1 기준 A (실제 시작일) 사용처 — 전수 확인

| 적용 위치 | 파일:라인 | 검증 |
|---|---|---|
| `oneShiftPerDay` | ConstraintProvider:203 | ✅ `shift.getStart().toLocalDate()` |
| `desiredDayForEmployee` | ConstraintProvider:210 | ✅ `shift.getStart().toLocalDate()` |
| `max15NightShiftsPerMonth` | ConstraintProvider:193 | ✅ `YearMonth.from(shift.getStart())` |
| `OneShiftPerDayValidator` | OneShiftPerDayValidator:29 | ✅ `groupingBy(s -> s.getStart().toLocalDate())` |
| `SimultaneousLocationValidator` | SimultaneousLocationValidator:31 | ✅ `groupingBy(s -> s.getStart().toLocalDate())` |
| `NightHardConstraintValidator.validateMonthlyNightShiftLimit` | NightHardConstraintValidator:78 | ✅ `YearMonth.from(shift.getStart())` |
| `SchedulePrinter` | SchedulePrinter:42 | ✅ `groupingBy(shift -> shift.getStart().toLocalDate())` |
| `JsonScheduleExporter.buildEmployeeShiftMap` | JsonScheduleExporter:265 | ✅ `shift.getStart().toLocalDate()` |
| `EmployeeViewExporter` | EmployeeViewExporter:109 | ✅ `shift.getStart().toLocalDate()` |
| `LocationViewExporter` | LocationViewExporter:36 | ✅ `shift.getStart().toLocalDate()` |
| `StatisticsViewExporter` | StatisticsViewExporter:103 | ✅ `shift.getStart().toLocalDate()` |
| `TimelineViewExporter` | TimelineViewExporter:101 | ✅ `shift.getStart().toLocalDate()` |
| `WorkerResource` | WorkerResource:109 | ✅ `start.toLocalDate()` |

### 8.2 기준 B (논리일) 사용처 — 전수 확인

| 적용 위치 | 파일:라인 | 검증 |
|---|---|---|
| `noFourConsecutiveNightShifts` | ConstraintProvider:125,129,133 | ✅ `getNightLogicalDate(...)` |
| `minimizeThreeConsecutiveNightShifts` | ConstraintProvider:161,165 | ✅ `getNightLogicalDate(...)` |
| `atLeast48HoursAfterTwoConsecutiveNightShifts` | ConstraintProvider:174-175 | ✅ `getNightLogicalDate(...)` (연속 판별에만) |
| `NightHardConstraintValidator.validateNoFourConsecutiveNightShifts` | NightHardConstraintValidator:55 | ✅ `resolveLogicalDate(nightShift)` |
| `JsonScheduleExporter.resolveLogicalDateForExport` | JsonScheduleExporter:186 | ✅ `resolveLogicalDate(shift)` |

### 8.3 기준 C (복합 매칭) 사용처 — 전수 확인

| 적용 위치 | 파일:라인 | 검증 |
|---|---|---|
| `undesiredDayForEmployee` | ConstraintProvider:226-227 | ✅ `matchesActualOrLogicalDate(...)` |
| `logUndesiredSoftDiagnostics` | SolutionValidator:102-103 | ✅ `matchesActualOrLogicalDate(...)` |
| `SolverRunnerTest` | SolverRunnerTest:77-78 | ✅ `matchesActualOrLogicalDate(...)` |

### 8.4 모순 사례 — 전수 확인

| 모순 | 검증 | 상세 |
|---|---|---|
| 모순 1: UNDESIRED vs DESIRED 비대칭 | ✅ | `matchesActualOrLogicalDate` vs `shift.getStart().toLocalDate()` 코드 차이 확인 |
| 모순 2: Export vs 통계 불일치 | ✅ | `toShiftDetailDto`(논리일) vs `buildEmployeeShiftMap`(시작일) 코드 차이 확인 |
| 모순 3: oneShiftPerDay 한계 | ✅ | Joiners.equal이 시작일 기준이므로 동일 확인 |
| 모순 4: 월간 Night 집계 기준 | ✅ | 시작월 기준, 논리일과의 괴리 존재 확인 |

### 8.5 AvailabilityValidator 죽은 코드

```java
// AvailabilityValidator.java:24-27 (전체 메서드 바디)
public void validate(...) {
    // UNAVAILABLE 타입이 제거되어 별도의 가용성 위반 검증은 불필요합니다.
    // DESIRED / UNDESIRED는 OptaPlanner 제약조건으로 처리됩니다.
}
```

✅ 분석 문서 주장과 일치합니다. 메서드 바디에 실행 가능한 코드가 단 한 줄도 없습니다.

### 8.6 주말 및 휴일 근무 공정성 기준 (Fairness Burden Criteria) 검증 및 통일

공정성 스케줄링을 위해 도입된 연간 야간/휴일 근무 누적 부담 점수(`fairnessBurdenScore`) 산출 시 적용되는 주말 및 휴일 근무의 판정 기준은 다음과 같습니다. 사용자 제안에 따라 야간 근무의 주말 및 휴일 부담 판정 기준을 완전히 대칭적으로 통정화하여 일관성 있게 구현하였습니다. 해당 기준은 `FairnessBurdenCalculator.java` 및 `ShiftDateMatcher.java`를 통해 구현 및 검증되었습니다.

#### 1) 통합된 대칭적 판정 규칙
주말 및 휴일 근무 판정은 근무 종류(야간 vs 비야간)의 물리적 시간 경계 특성을 반영하여 일관된 규칙으로 통일되었습니다.

* **야간 근무 (N)**: **퇴근일(근무 종료일 = 논리일 + 1일)** 기준
  * 야간 근무는 밤에 출근하여 다음 날 아침에 퇴근하므로, **퇴근하는 날**이 주말이거나 휴일일 때 근무 부담이 부여됩니다.
  * **주말 판정**: 퇴근일이 토요일 또는 일요일일 때 적용 (즉, **금요일 N** 및 **토요일 N**)
  * **휴일 판정**: 퇴근일이 공휴일 날짜에 포함될 때 적용 (즉, **휴일 전일 N** 및 **휴일 당일 새벽 N**)
    * *예: 12월 25일이 크리스마스 휴일인 경우, 24일 23:00 N 근무(퇴근일이 25일 휴일이므로 적용) 및 25일 01:00 N 근무(퇴근일이 25일 휴일이므로 적용)*
    * *휴일 당일 밤 시작 야간 근무(25일 23:00 N)는 퇴근하는 26일 아침이 평일이므로 휴일 근무에서 제외되어 주말의 '일요일 야간 N(퇴근일 월요일)' 제외와 완벽히 대칭을 이룹니다.*
* **주간/저녁 근무 (D, E)**: **출근일(실제 시작일)** 기준
  * D, E 근무는 당일에 시작하여 당일에 퇴근하므로, **출근일 당일**이 주말이거나 휴일일 때 근무 부담이 부여됩니다.
  * **주말 판정**: 출근일이 토요일 또는 일요일일 때 적용 (즉, **토요일 D/E** 및 **일요일 D/E**)
  * **휴일 판정**: 출근일이 공휴일 날짜에 포함될 때 적용 (즉, **휴일 당일 D/E**)

#### 2) 최종 근무 부담 판정 범위 요약

| 근무 유형 | 주말 근무 부담 대상 | 휴일 근무 부담 대상 |
| :--- | :--- | :--- |
| **야간 근무 (N)** | **금요일 N**, **토요일 N** *(일요일 N 제외)* | **휴일 전일 N**, **휴일 당일 새벽 N** *(휴일 당일 밤 N 제외)* |
| **주간/저녁 근무 (D, E)** | **토요일 D, E**, **일요일 D, E** | **휴일 당일 D, E** |

*결과적으로, 주말 및 휴일 판정 기준이 완벽하게 결합되어 **'야간 근무는 퇴근일(종료일) 기준, 주간/저녁 근무는 출근일(시작일) 기준'**이라는 매우 아름답고 논리적으로 일치하는 규칙으로 정립되었으며, 이를 통해 도메인 모델의 일관성과 코드의 유지보수성이 크게 향상되었습니다.*

---

## 9. 테스트 영향 분석

분석 문서 §5는 "SolverRunnerTest 및 EmployeeSchedulingConstraintProviderTest의 관련 테스트 케이스 업데이트"를 권장하지만, 구체적인 영향 범위를 명시하지 않습니다. 실제 영향 범위는 다음과 같습니다.

### 9.1 Directly affected (직접 변경 필요)

#### SolverRunnerTest.java (1개 테스트 메서드)

```java
// line 77-78: calculateUndesiredSoftScore() 내부
boolean hasUndesiredMatch = undesiredDates.stream()
    .anyMatch(undesiredDate -> ShiftDateMatcher.matchesActualOrLogicalDate(shift, undesiredDate));
```

변경: `matchesActualOrLogicalDate` → `shift.getStart().toLocalDate()`와 동등한 비교로 변경 필요.

#### EmployeeSchedulingConstraintProviderTest.java (7개 테스트 메서드)

| 테스트명 | 라인 | 영향 | 비고 |
|---|---|---|---|
| `MatchesNightLogicalDate` | 530 | **직접 영향** | 논리일 매칭 테스트 → 시작일 기준으로 기대값 변경 필요 |
| `MatchesNightActualDate` | 540 | 영향 없음 | 실제 시작일 매칭 테스트 → 동일하게 적용 |
| `NightBeforeCutoff_UsesPreviousLogicalDate` | 553 | **삭제 또는 변경** | 논리일 매칭이 사라지므로 이 테스트의 의미 상실 |
| `NightAtCutoff_DoesNotUsePreviousLogicalDate` | 566 | **삭제 또는 변경** | 동일. 컷오프 경계 테스트가 무의미해짐 |
| `AppliesLogicalAndActualDatesOncePerShift` | 579 | **직접 영향** | 논리일+실제일 중복 테스트 → 시작일 기준 단순화 필요 |
| `DeduplicatesSameDateMatchesPerShift` | 593 | 영향 없음 | 중복 제거 로직은 동일 |
| `IgnoresPinnedShift` | 606 | 영향 없음 | Pinned shift 동작은 동일 |

### 9.2 Indirectly affected (간접 영향, 변경 불필요)

| 파일 | 이유 |
|---|---|
| `NightHardConstraintValidatorTest.java` | ShiftDateMatcher를 직접 사용하지 않음 |
| `SolverRunnerTerminationPolicyTest.java` | 날짜 관련 로직 없음 |

### 9.3 새로운 테스트 권장 사항

변경 시 다음 시나리오에 대한 테스트를 추가하는 것이 좋습니다:
- 월요일 UNDESIRED + 일요일 23:00 N 시작 → **페널티 없음** (변경 후 의도된 동작)
- 월요일 UNDESIRED + 월요일 00:00 N 시작 → **페널티** (시작일 기준 매칭)

---

## 10. 종합 평가 및 권장 수정 사항

### 10.1 문서의 강점

1. **3가지 기준으로 체계적으로 분석**하여 혼란의 근원을 명확히 식별함
2. **전면 통일이 아닌 목적별 분리**라는 현실적인 접근법 채택
3. **구체적인 모순 사례**(4가지)를 들어 문제를 가시화함
4. **단계적 실행 계획**으로 즉시 실천 가능한 방향 제시
5. **기존 문서와의 관계**를 명확히 정리함

### 10.2 수정 권장 사항 (우선순위 순)

| # | 수정 사항 | 중요도 | 난이도 | 위치 |
|---|---|---|---|---|
| 1 | §2 기준 B에서 `atLeast32HoursFromNightToNextDayShift` 제거. 사실 오류임. | 🔴 높음 | 낮음 | §2 표 |
| 2 | §2 기준 B에서 `atLeast48HoursAfterTwoConsecutiveNightShifts`를 "논리일(부분)"으로 명확히 표기 | 🟡 중간 | 낮음 | §2 표 |
| 3 | §4 제안(2)에 트레이드오프 섹션 추가 — Night→Evening 시나리오가 미커버됨을 명시 | 🟡 중간 | 중간 | §4 제안(2) |
| 4 | §6 실행 순서에 `ShiftDateMatcher` 정리 단계 추가 | 🟡 중간 | 낮음 | §6 |
| 5 | §4 제안(2)에서 "업계 표준" 주장 완화 또는 출처 추가 | 🟢 낮음 | 낮음 | §4 제안(2) |
| 6 | §9 테스트 영향 분석 구체화 (이 문서 §9 내용 참고) | 🟢 낮음 | 중간 | §5 |

### 10.3 최종 결론

**분석 문서의 방향과 결론은 타당합니다.** 특히 `undesiredDayForEmployee`를 시작일 기준으로 변경하고, Export 날짜를 시작일 기준으로 통일하며, 논리일을 연속 Night 판단으로 제한하자는 제안은 코드 검증과 논리적 추론 모두에서 지지됩니다.

다만 위에서 식별한 2개의 사실 오류, 1개의 미해결 트레이드오프, 2개의 분석 누락, 1개의 근거 부족 주장을 보강하면 문서의 완성도와 신뢰성이 크게 향상될 것입니다.

가장 시급한 수정은 **§2 기준 B 표에서 `atLeast32HoursFromNightToNextDayShift` 제거**입니다. 이는 명백한 사실 오류로, 수정하지 않으면 향후 유지보수 과정에서 오해를 불러일으킬 수 있습니다.
