# 날짜 기준 통일 분석 보고서

> 분석 일자: 2026-05-21  
> 분석 대상: `org.acme.solver.algorithm.EmployeeSchedulingConstraintProvider` 및 관련 모듈 전반  
> 관련 문서: `CONSTRAINT_PROVIDER_DEEP_ANALYSIS.md`, `CONSTRAINT_PROVIDER_ANALYSIS.md`

---

## 1. 배경: UNAVAILABLE 제거로 인한 맥락 변화

기존 `CONSTRAINT_PROVIDER_DEEP_ANALYSIS.md`에서 식별된 핵심 이슈 중 하나는 **`UNAVAILABLE`(하드)과 `UNDESIRED`(소프트)의 날짜 매칭 정책 불일치**였습니다. 그러나 현재 코드베이스에서 `UNAVAILABLE` 타입은 제거되었고, `AvailabilityValidator`는 사실상 죽은 코드 상태입니다. 이에 따라 하드/소프트 간 불일치 문제는 근본적으로 해소되었습니다.

그러나 이 변화는 **남아있는 날짜 기준들의 혼재**라는 새로운 문제를 더욱 부각시켰습니다. 현재 코드베이스는 3가지 상이한 날짜 기준을 동시에 사용하고 있으며, 이로 인해 Soft 제약 난부의 비대칭, Export/통계 불일치 등의 모순이 발생하고 있습니다.

---

## 2. 현재 혼재된 3가지 날짜 기준

### 기준 A: 실제 시작일 (`shift.getStart().toLocalDate()`)
가장 많이 사용되는 기준입니다.

| 적용 위치 | 목적 |
|---|---|
| `EmployeeSchedulingConstraintProvider.oneShiftPerDay()` | 하루 1교대 제약 (OptaPlanner) |
| `EmployeeSchedulingConstraintProvider.desiredDayForEmployee()` | 희망 근무일 reward (OptaPlanner) |
| `EmployeeSchedulingConstraintProvider.max15NightShiftsPerMonth()` | 월간 Night 횟수 집계 (OptaPlanner) |
| `OneShiftPerDayValidator` | 하루 1교대 사후 검증 |
| `SimultaneousLocationValidator` | 날짜별 동시 다중 위치 검증 |
| `NightHardConstraintValidator.validateMonthlyNightShiftLimit()` | 월간 Night 횟수 사후 검증 |
| `SchedulePrinter` | 출력용 날짜 그룹화 |
| `JsonScheduleExporter.buildEmployeeShiftMap()` | JSON 직원별 날짜별 매핑 |
| `EmployeeViewExporter`, `LocationViewExporter`, `StatisticsViewExporter`, `TimelineViewExporter` | 거의 모든 Export |
| `WorkerResource` | API 응답 날짜 처리 |

### 기준 B: 논리일 (`ShiftDateMatcher.resolveLogicalDate(shift)`)
Night 근무에만 특수 적용되는 기준입니다.

| 적용 위치 | 목적 |
|---|---|
| `EmployeeSchedulingConstraintProvider.noFourConsecutiveNightShifts()` | 4연속 Night 하드 제약 |
| `EmployeeSchedulingConstraintProvider.minimizeThreeConsecutiveNightShifts()` | 3연속 Night 소프트 제약 |
| `EmployeeSchedulingConstraintProvider.atLeast48HoursAfterTwoConsecutiveNightShifts()` | 2연속 Night 판별(논리일) + 휴식 계산(실제 시간) — 혼합 |
| `NightHardConstraintValidator.validateNoFourConsecutiveNightShifts()` | 4연속 Night 사후 검증 |
| `JsonScheduleExporter.resolveLogicalDateForExport()` | **JSON Export 시 Shift 날짜 표시** |
| `SolverRunnerTest` | 연속 Night 테스트 검증 |

### 기준 C: 복합 매칭 (`matchesActualOrLogicalDate()` = 실제 오버랩 OR 논리일)
단 3곳에서만 사용됩니다.

| 적용 위치 | 목적 |
|---|---|
| `EmployeeSchedulingConstraintProvider.undesiredDayForEmployee()` | 비선호 근무일 penalty (OptaPlanner) |
| `SolutionValidator.logUndesiredSoftDiagnostics()` | 비선호 매칭 사후 진단 로깅 |
| `SolverRunnerTest` | 비선호 매칭 테스트 검증 |

---

## 3. 혼재로 인한 구체적 모순 사례

### 모순 1: `UNDESIRED`와 `DESIRED`의 비대칭 (Soft 제약 난부 불일치)

| 시나리오 | `UNDESIRED` (월요일 등록) | `DESIRED` (월요일 등록) |
|---|---|---|
| 일요일 23:00 ~ 월요일 07:00 N | **penalty** (논리일=월요일) | **reward 없음** (시작일=일요일) |
| 월요일 00:00 ~ 08:00 N | **penalty** (시작일=월요일) | **reward** (시작일=월요일) |

→ "비선호는 넓게 잡고, 희망은 좁게 잡는" 불균형. 사용자 입장에서 "월요일 근무를 원함"과 "월요일 근무를 피함"이 대칭적으로 작동하지 않습니다.

### 모순 2: Export 날짜와 통계 집계 날짜 불일치

- `JsonScheduleExporter.toShiftDetailDto()`: Night 근무를 **논리일** 기준으로 JSON에 표시 (예: 1월 1일 00:00~08:00 N → `date` 필드에 12월 31일 표시)
- `JsonScheduleExporter.buildEmployeeShiftMap()`: 동일 근무를 **시작일(1월 1일)** 기준으로 집계
- `buildStatistics()`: `totalWorkDays`를 시작일 기준으로 카운트

→ 사용자가 JSON을 볼 때 "12월 31일에 Night가 찍혀 있는데, 통계는 1월 근무일로 잡힌다"는 혼란을 겪을 수 있습니다.

### 모순 3: `oneShiftPerDay`와 논리일의 시각 차이

- **1월 5일 23:00 ~ 1월 6일 07:00 N** + **1월 6일 08:00 ~ 16:00 D**
  - `oneShiftPerDay`: 시작일이 1월 5일과 1월 6일이므로 **penalty 없음**
  - 실제로는 1시간 연속 근무 (→ `noOverlappingShifts`로 커버됨)
  - 논리일은 1월 5일(N)과 1월 6일(D)이므로 연속 Night 아님

이는 현재 설계상 의도된 동작이지만, `oneShiftPerDay`가 "24시간 내 중복 근무"보다는 "시작일 중복"을 금지하는 한계를 보여줍니다.

### 모순 4: 월간 Night 횟수 집계의 기준 불확실성

- `max15NightShiftsPerMonth()`: 실제 시작 월 기준 (1월 1일 00:00 N → 1월 카운트)
- Export/통계: 시작일 기준으로 동일
- 하지만 논리일 기준으로 보면 이 근무는 12월 근무입니다.

→ Night 근무의 "월 귀속"은 업계 표준상 **시작일 기준**이 맞습니다. 다만 논리일과의 괴리가 존재합니다.

---

## 4. 통일 방향 제안: 목적별 기준 분리 + Availability 매칭 통일

핵심 통찰은 다음과 같습니다: **"하나의 날짜 기준으로 모든 것을 통일하면 오히려 더 큰 모순이 생깁니다."**

각 기능이 묻는 질문이 다릅니다:
- `oneShiftPerDay`: "24시간 내에 중복 근무가 있는가?" → **실제 시간 기준**
- `noFourConsecutiveNightShifts`: "날짜 경계를 넘는 Night가 연속인가?" → **논리일 기준**
- `undesiredDayForEmployee`: "사용자가 '월요일 Off'라고 했을 때 어떤 근무를 막아야 하는가?" → **사용자 인식 기준**

따라서 통일 대상은 **"기준의 역할"이 아니라 "기준의 선택"**이어야 합니다.

### 제안 (1): 논리일(Logical Date)의 역할을 축소 — 연속 Night 판단 전용

논리일은 **"1월 6일 00:00 N과 1월 7일 00:00 N이 연속인가?"**를 판단하기 위해 존재하는 개념입니다. 이를 Export나 Availability까지 확장한 것이 현재 혼란의 원인입니다.

**변경 제안:**
- `JsonScheduleExporter.resolveLogicalDateForExport()`: **시작일 기준으로 변경**
  - 근거: 통계 집계 날짜와 일치시켜 사용자 혼란 방지. 업계 표준상 Night 근무는 시작일 기준으로 표기하는 것이 일반적입니다.
- 논리일 사용처: `noFourConsecutiveNightShifts`, `minimizeThreeConsecutiveNightShifts`, `NightHardConstraintValidator`에만 한정

### 제안 (2): Availability 매칭(`UNDESIRED`/`DESIRED`)을 시작일 기준으로 통일

`undesiredDayForEmployee`의 `matchesActualOrLogicalDate()`를 **폐기**하고, `desiredDayForEmployee`와 동일하게 `shift.getStart().toLocalDate()` 기준으로 변경합니다.

**근거:**
- `UNDESIRED`와 `DESIRED`의 비대칭 해소
- 일반적인 관행 및 다수의 스케줄링 시스템에서 사용하는 방식
- 사용자 직관: "월요일 Off = 월요일에 시작하는 근무는 안 된다"
- OptaPlanner `Joiners.equal` 최적화 유지 (성능)
- `Joiners.overlapping`이나 `noOverlappingShifts`가 이미 물리적 중복을 방지하므로, "일요일 밤~월요일 새벽 근무"는 휴식 제약으로 충분히 커버됨

**부작용:**
- 월요일 `UNDESIRED` 시, 일요일 23:00~월요일 07:00 N 근무가 배정될 수 있게 됨
- 이는 `atLeast32HoursFromNightToNextDayShift`나 `atLeast48HoursAfterTwoConsecutiveNightShifts`로 충분히 제어됨

**트레이드오프:**

다음 시나리오는 위 제약들로도 커버되지 않습니다:

| 시나리오 | 월요일 UNDESIRED로 차단? | 적용되는 제약 |
|---|---|---|
| 일요일 23:00~월요일 07:00 **N** → 월요일 14:00~22:00 **E** | **차단 안 됨** | `atLeast32Hours`는 Day shift에만 적용. `atLeast48Hours`는 2연속 Night가 필요. `noOverlappingShifts`는 중복 없음. |

이 시나리오(Night→Evening)에서는 아무 제약도 위반하지 않아 배정이 가능합니다. 그러나 이는 **수용 가능한 트레이드오프**입니다:

1. `UNDESIRED`는 Soft 제약(SOFT_UNDESIRED_INDEX=2)이므로 "가능하면 피하라"는 의도이지 절대 금지가 아님
2. `DESIRED`도 동일한 Soft 레벨로 대칭을 맞추는 것이 일관성 측면에서 더 중요
3. Night→Evening 전환 시나리오는 현실에서 드묾
4. "월요일 Off = 월요일에 시작하는 근무는 안 된다"는 사용자 직관과 일치

### 제안 (3): `oneShiftPerDay`는 실제 시작일 기준 유지

논리일 기준으로 바꾸면 1월 6일 00:00~08:00 N(논리일=1월 5일)과 1월 6일 09:00~17:00 D(시작일=1월 6일)가 서로 다른 날로 인식되어 **17시간 연속 근무가 허용되는 치명적 버그**가 발생합니다. 따라서 `oneShiftPerDay`는 반드시 실제 시작일 기준을 유지해야 합니다.

### 제안 (4): 월간 Night 횟수는 실제 시작월 기준 유지

급여/근태 시스템 호환성을 위해 시작일 기준 월 집계는 업계 표준입니다. 이를 논리일 기준으로 변경하면 "1월 1일 00:00 N이 12월 근무로 집계된다"는 혼란이 생깁니다.

---

## 5. 수정 대상 요약

| 파일/메서드 | 현재 기준 | 제안 기준 | 이유 |
|---|---|---|---|
| `undesiredDayForEmployee` (ConstraintProvider) | 복합(오버랩+논리일) | 시작일 | DESIRED와 대칭, 업계 표준, 성능 |
| `desiredDayForEmployee` (ConstraintProvider) | 시작일 | **유지** | 이미 올바름 |
| `logUndesiredSoftDiagnostics` (SolutionValidator) | 복합 | 시작일 | UNDESIRED와 일치 |
| `resolveLogicalDateForExport` (JsonScheduleExporter) | 논리일 | 시작일 | 통계/Export 일치, 사용자 혼란 방지 |
| `noFourConsecutiveNightShifts` | 논리일 | **유지** | 연속성 판단은 논리일이 필요 |
| `minimizeThreeConsecutiveNightShifts` | 논리일 | **유지** | 동일 |
| `atLeast32HoursFromNightToNextDayShift` | 실제 시간 | **유지** | 논리일 미사용, shiftCode로 Night 식별 후 실제 시간 비교 |
| `atLeast48HoursAfterTwoConsecutiveNightShifts` | 논리일(부분) | **유지** | 연속 판별에만 논리일 사용, 휴식 계산은 실제 시간 |
| `oneShiftPerDay` | 시작일 | **유지** | 24시간 중복 방지의 물리적 현실 |
| `max15NightShiftsPerMonth` | 시작월 | **유지** | 급여/근태 표준 |
| `SolverRunnerTest` undesired 검증 | 복합 | 시작일 | UNDESIRED 변경 반영 |

### 테스트 영향 분석

`EmployeeSchedulingConstraintProviderTest`의 UNDESIRED 관련 테스트 8개에 영향이 있습니다.

| 테스트명 | 라인 | 현재 기댓값 | 변경 후 기댓값 | 변경 여부 | 사유 |
|---|---|---|---|---|---|
| `softScoreLevels_UndesiredAndFair` | 514 | `scores([0], [0,0,-480,0,-1,0])` | 동일 | 없음 | 실제 시작일 기준으로 이미 매칭 |
| `undesiredDayForEmployee_MatchesNightLogicalDate` | 527 | `penalizesBy(480)` | `penalizesBy(0)` | **있음** | 논리일 매칭 제거 |
| `undesiredDayForEmployee_MatchesNightActualDate` | 540 | `penalizesBy(480)` | 동일 | 없음 | 실제 시작일 기준으로 이미 매칭 |
| `undesiredDayForEmployee_NightBeforeCutoff_UsesPreviousLogicalDate` | 553 | `penalizesBy(480)` | `penalizesBy(0)` | **있음** | 논리일 매칭 제거 |
| `undesiredDayForEmployee_NightAtCutoff_DoesNotUsePreviousLogicalDate` | 566 | `penalizesBy(0)` | 동일 | 없음 | 컷오프 경계에서 실제일도 불일치 |
| `undesiredDayForEmployee_AppliesLogicalAndActualDatesOncePerShift` | 579 | `penalizesBy(480)` | 동일 | 없음 | 실제일 매칭만 남아도 1회 페널티 |
| `undesiredDayForEmployee_DeduplicatesSameDateMatchesPerShift` | 593 | `penalizesBy(480)` | 동일 | 없음 | 중복 제거 로직은 동일 |
| `undesiredDayForEmployee_IgnoresPinnedShift` | 606 | `penalizesBy(0)` | 동일 | 없음 | Pinned shift 동작은 동일 |

추가로 `SolverRunnerTest.calculateUndesiredSoftScore()` (line 77-78)의 `matchesActualOrLogicalDate` 호출도 `shift.getStart().toLocalDate()` 기준 비교로 변경이 필요합니다.

---

## 6. 핵심 메시지

> "Night 근무의 논리일"은 **연속 Night 판단**이라는 단 하나의 목적을 위해 존재하는 개념입니다. 이를 Export나 Availability까지 확장한 것이 현재 혼란의 근원이며, 논리일의 역할을 축소하고 나머지는 시작일 기준으로 일원화하는 것이 코드의 일관성과 사용자 경험 모두를 개선합니다.

**권장하는 실행 순서:**
1. **즉시**: `undesiredDayForEmployee`를 시작일 기준으로 변경 (`Joiners.equal` 적용)
2. **정리**: `ShiftDateMatcher`에서 `matchesActualOrLogicalDate`와 `overlapsActualDate` 제거. `resolveLogicalDate`는 연속 Night 판단용으로 유지
3. **단기**: `JsonScheduleExporter`의 Export 날짜를 시작일 기준으로 변경
4. **검증**: `SolverRunnerTest` 및 `EmployeeSchedulingConstraintProviderTest`의 관련 테스트 케이스 업데이트
5. **장기**: 문서(`SHIFT_DATE_POLICY.md` 등)에 "날짜 기준 정책"을 명시하여 향후 혼란 방지

---

## 7. 참고: 기존 문서와의 관계

- `CONSTRAINT_PROVIDER_DEEP_ANALYSIS.md`의 **제안 B**(`UNAVAILABLE`과 정책 통일)는 `UNAVAILABLE` 타입 제거로 인해 **구현 대상이 소멸**하였습니다.
- 본 문서는 기존 분석의 후속으로, 남아있는 **Soft 제약 난부 불일치**와 **Export/통계 불일치**를 해결하는 방향을 제시합니다.
- 기존 문서의 **제안 A**("정책 문서화 및 사용자 안내")와 **제안 C**("설정 가능한 매칭 정책")는 여전히 유효하며, 본 문서의 제안이 우선 적용된 후 장기적으로 검토할 수 있습니다.
