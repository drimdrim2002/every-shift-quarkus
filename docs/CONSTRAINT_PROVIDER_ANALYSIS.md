# OptaPlanner 제약 조건 분석 (EmployeeSchedulingConstraintProvider)

본 문서는 `org.acme.solver.algorithm.EmployeeSchedulingConstraintProvider` 클래스를 분석하여, 직원 스케줄링 최적화 문제를 해결하기 위한 **제약 조건(Constraints)** 의 정의와 로직을 기술합니다.

이 클래스는 `ConstraintProvider` 인터페이스를 구현하며, 스케줄이 유효한지(Hard Constraint)와 얼마나 효율적인지(Soft Constraint)를 판단하는 규칙들을 담고 있습니다.

## 1. 개요
*   **역할**: `Shift`(근무)와 `Employee`(직원), `Availability`(가용성) 간의 규칙을 정의하여 점수를 계산합니다.
*   **점수 체계**: `BendableScore`(`1 hard + 6 soft`)를 사용합니다.
    *   **Hard Score(레벨 1)**: 필수 규칙. 어기면 **불가능한 스케줄**로 간주됩니다 (예: 한 사람이 동시에 두 곳에서 근무).
    *   **Soft Score(레벨 6)**: 목적식 우선순위로 비교됩니다.
        * `soft[0]`: 2연속 Night 후 다음 근무까지 48시간 휴식 부족분
        * `soft[1]`: Night 후 다음 Day shift까지 32시간 휴식 부족분
        * `soft[2]`: `undesired`
        * `soft[3]`: `3연속 Night 최소화`
        * `soft[4]`: `fair distribution`
        * `soft[5]`: `desired`

## 2. 제약 조건 상세 분석

### A. Hard Constraints (필수 조건)
물리적으로 불가능하거나 회사 규정상 반드시 지켜야 하는 규칙들입니다.

#### 1. `requiredSkill` (필수 기술)
*   **내용**: 특정 `Shift`가 요구하는 기술(`requiredSkill`)을 배정된 `Employee`가 가지고 있는지 확인합니다.
*   **패널티**: 직원이 해당 기술을 보유하고 있지 않으면 점수를 깎습니다 (`ONE_HARD`).

#### 2. `noOverlappingShifts` (근무 시간 중복 금지)
*   **내용**: 동일한 직원이 배정된 두 개의 `Shift`가 시간상으로 겹치는지 확인합니다.
*   **패널티**: 겹치는 시간(분 단위)만큼 점수를 깎습니다.
*   **로직**: `getMinuteOverlap` 헬퍼 메서드를 사용하여 겹치는 분(minute)을 계산합니다.

#### 3. `atLeast12HoursBetweenTwoShifts` (최소 휴식 시간)
*   **내용**: 한 직원의 연속된 두 근무 사이 휴식이 **12시간 미만**이면 패널티가 발생합니다.
*   **패널티**: `12시간 - 실제 휴식시간`(분 단위)만큼 감점합니다.

#### 4. `noFourConsecutiveNightShifts` (4연속 Night 금지)
*   **내용**: 동일 직원에게 Night 근무가 논리일 기준 4일 연속 배정되는지 확인합니다.
*   **패널티**: 4연속 Night 패턴이 발견되면 점수를 깎습니다 (`ONE_HARD`).

#### 5. `max15NightShiftsPerMonth` (월 15회 Night 제한)
*   **내용**: 직원별 월간 Night 근무 횟수가 15회를 초과하는지 확인합니다.
*   **패널티**: 초과한 Night 근무 횟수만큼 점수를 깎습니다 (`ONE_HARD`).

#### 6. `oneShiftPerDay` (하루 1교대 제한)
*   **내용**: 한 직원이 같은 날짜(`LocalDate`)에 두 개 이상의 근무를 할 수 없습니다.
*   **패널티**: 같은 날 두 번 배정되면 점수를 깎습니다.

#### 7. `unavailableEmployee` (근무 불가능일 준수)
*   **내용**: 직원의 `Availability`가 `UNAVAILABLE`(근무 불가)로 설정된 날짜에 근무가 배정되었는지 확인합니다.
*   **패널티**: 해당 근무의 길이(분 단위)만큼 점수를 깎습니다.

### B. Soft Constraints (선호 조건)
휴식 품질과 안전, 직원 선호 반영, 근무 분배 균형을 개선하기 위한 규칙들입니다.

#### 8. `atLeast48HoursAfterTwoConsecutiveNightShifts` (2연속 Night 후 48시간 휴식, Soft 1순위)
*   **내용**: 직원이 2연속 Night 근무 후 다음 근무까지 최소 48시간을 쉬었는지 확인합니다.
*   **패널티**: 부족한 휴식 시간(분 단위)만큼 `soft[0]` 점수를 깎습니다.

#### 9. `atLeast32HoursFromNightToNextDayShift` (Night 후 Day shift까지 32시간 휴식, Soft 2순위)
*   **내용**: 직원이 Night 근무 후 다음 Day shift까지 최소 32시간을 쉬었는지 확인합니다.
*   **패널티**: 부족한 휴식 시간(분 단위)만큼 `soft[1]` 점수를 깎습니다.

#### 10. `undesiredDayForEmployee` (비선호 근무일, Soft 3순위)
*   **내용**: 직원이 `UNDESIRED`(근무 기피)로 설정한 날짜에 근무가 배정되었는지 확인합니다.
*   **패널티**: 근무 시간(분 단위)만큼 `soft[2]` 점수를 깎습니다.
*   **중요 정책**: 실제일/논리일이 동시에 매칭되거나 동일 날짜 요청이 중복되어도, **시프트 1건당 패널티는 최대 1회**만 적용됩니다.
*   **Night 논리일 정책**: `ShiftDateMatcher`의 컷오프(06:00) 규칙을 따릅니다. 즉, Night 시작 시각이 `00:00~05:59`면 `-1일`, `06:00~23:59`면 `당일`로 처리됩니다.

#### 11. `minimizeThreeConsecutiveNightShifts` (3연속 Night 최소화, Soft 4순위)
*   **내용**: 동일 직원에게 Night 근무가 논리일 기준 3일 연속으로 형성되는 구간 수를 최소화합니다.
*   **패널티**: 3연속 윈도우 1건당 `soft[3]` 점수를 1 깎습니다.

#### 12. `fairShiftDistribution` (근무 분배 균형, Soft 5순위)
*   **내용**: 직원별 근무 유형(Day/Evening/Night)별 시프트 수를 집계해 불균형을 패널티화합니다.
*   **패널티**: 근무 유형별 가중치와 `shiftCount * shiftCount`를 곱한 값만큼 `soft[4]` 점수를 깎습니다.

#### 13. `desiredDayForEmployee` (선호 근무일, Soft 6순위)
*   **내용**: 직원이 `DESIRED`(근무 희망)로 설정한 날짜에 근무가 배정되었는지 확인합니다.
*   **보상 (Reward)**: 근무 시간(분 단위)만큼 `soft[5]` 점수를 **더해줍니다**.

## 3. 헬퍼 메서드
*   **`getMinuteOverlap(Shift shift1, Shift shift2)`**: 두 근무 시간 사이의 겹치는 시간을 분 단위로 계산합니다. 시작 시간 중 늦은 시간과 종료 시간 중 빠른 시간을 비교하여 계산합니다.
*   **`getShiftDurationInMinutes(Shift shift)`**: 하나의 근무 시간을 분 단위로 계산합니다.

## 4. 종합 의견
*   전반적으로 OptaPlanner의 Stream API를 사용하여 간결하게 작성되었습니다.
*   모든 제약 조건은 `Shift` 객체를 중심으로 `Employee`와 `Availability`를 조인하여 검증하고 있습니다.
