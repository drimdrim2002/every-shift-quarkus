# OptaPlanner 제약 조건 분석 (EmployeeSchedulingConstraintProvider)

본 문서는 `org.acme.solver.algorithm.EmployeeSchedulingConstraintProvider` 클래스를 분석하여, 직원 스케줄링 최적화 문제를 해결하기 위한 **제약 조건(Constraints)** 의 정의와 로직을 기술합니다.

이 클래스는 `ConstraintProvider` 인터페이스를 구현하며, 스케줄이 유효한지(Hard Constraint)와 얼마나 효율적인지(Soft Constraint)를 판단하는 규칙들을 담고 있습니다.

## 1. 개요
*   **역할**: `Shift`(근무)와 `Employee`(직원), `Availability`(가용성) 간의 규칙을 정의하여 점수를 계산합니다.
*   **점수 체계**: `HardSoftScore`를 사용합니다.
    *   **Hard Score**: 필수 규칙. 어기면 **불가능한 스케줄**로 간주됩니다 (예: 한 사람이 동시에 두 곳에서 근무).
    *   **Soft Score**: 선호 규칙. 어기더라도 스케줄은 가능하지만, **품질이 낮은 스케줄**로 간주됩니다 (예: 직원이 원하지 않는 날 근무).

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
*   **내용**: 한 직원의 연속된 두 근무 사이에 충분한 휴식 시간이 있는지 확인합니다.
*   **특이사항 (코드와 이름의 불일치)**:
    *   제약 조건의 이름은 "최소 **12시간**"입니다.
    *   하지만 실제 필터 로직(`filter`)은 휴식 시간이 **10시간 미만**인 경우에만 패널티를 적용합니다. (`toHours() < 10`)
    *   패널티 점수 계산은 **12시간(720분)** 을 기준으로 부족한 시간만큼 부과합니다. (`(12 * 60) - breakLength`)
    *   *분석 의견*: 로직상 10시간 이상 12시간 미만의 휴식은 허용하고 있으므로, 이름과 로직 간의 불일치가 있어 수정이 필요해 보입니다.

#### 4. `oneShiftPerDay` (하루 1교대 제한)
*   **내용**: 한 직원이 같은 날짜(`LocalDate`)에 두 개 이상의 근무를 할 수 없습니다.
*   **패널티**: 같은 날 두 번 배정되면 점수를 깎습니다.

#### 5. `unavailableEmployee` (근무 불가능일 준수)
*   **내용**: 직원의 `Availability`가 `UNAVAILABLE`(근무 불가)로 설정된 날짜에 근무가 배정되었는지 확인합니다.
*   **패널티**: 해당 근무의 길이(분 단위)만큼 점수를 깎습니다.

### B. Soft Constraints (선호 조건)
직원의 만족도를 높이기 위한 규칙들입니다.

#### 6. `desiredDayForEmployee` (선호 근무일)
*   **내용**: 직원이 `DESIRED`(근무 희망)로 설정한 날짜에 근무가 배정되었는지 확인합니다.
*   **보상 (Reward)**: 근무 시간(분 단위)만큼 점수를 **더해줍니다** (`ONE_SOFT`).

#### 7. `undesiredDayForEmployee` (비선호 근무일)
*   **내용**: 직원이 `UNDESIRED`(근무 기피)로 설정한 날짜에 근무가 배정되었는지 확인합니다.
*   **패널티**: 근무 시간(분 단위)만큼 점수를 깎습니다.

## 3. 헬퍼 메서드
*   **`getMinuteOverlap(Shift shift1, Shift shift2)`**: 두 근무 시간 사이의 겹치는 시간을 분 단위로 계산합니다. 시작 시간 중 늦은 시간과 종료 시간 중 빠른 시간을 비교하여 계산합니다.
*   **`getShiftDurationInMinutes(Shift shift)`**: 하나의 근무 시간을 분 단위로 계산합니다.

## 4. 종합 의견
*   전반적으로 OptaPlanner의 Stream API를 사용하여 간결하게 작성되었습니다.
*   **`atLeast12HoursBetweenTwoShifts`** 에서 10시간과 12시간 사이의 로직 불일치는 의도된 것인지(최소 10시간은 넘어야 하지만 12시간이 목표인지), 아니면 단순 실수인지 확인 후 수정하는 것이 좋습니다.
*   모든 제약 조건은 `Shift` 객체를 중심으로 `Employee`와 `Availability`를 조인하여 검증하고 있습니다.
