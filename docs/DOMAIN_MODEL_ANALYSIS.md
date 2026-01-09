# OptaPlanner 도메인 모델 및 데이터 흐름 분석

본 문서는 `DemoDataGenerator.java` 및 `domain` 패키지 분석을 통해 직원 일정 관리(Employee Scheduling) 애플리케이션의 OptaPlanner 도메인 설계를 기술합니다.

## 1. OptaPlanner 도메인 모델 구조 (Class Role Analysis)

이 프로젝트는 **직원(Employee)을 적절한 교대 근무(Shift)에 배정**하는 자원 할당 문제입니다.

| 클래스 | OptaPlanner 역할 | 설명 |
| :--- | :--- | :--- |
| **`EmployeeSchedule`** | **Planning Solution** | 문제 해결을 위한 전체 데이터셋을 담는 컨테이너입니다. 점수(`Score`), 문제 사실(Facts), 계획 엔티티(Entities)를 모두 포함합니다. |
| **`Shift`** | **Planning Entity** | **변하는 대상**입니다. 최적화 과정에서 Solver가 변경할 수 있는 객체입니다. |
| **`Employee`** | **Problem Fact** / Value | Shift에 할당될 수 있는 후보군(Value Range)입니다. Solver가 생성하거나 변경하지 않고 참조만 합니다. |
| **`Availability`** | **Problem Fact** | 제약 조건 계산에 사용되는 불변 정보입니다. (예: 특정 날짜, 특정 직원의 근무 가능 여부) |
| **`ScheduleState`** | **Problem Fact** (Global) | 전체 일정의 상태(작성 중, 배포됨 등)를 관리하며, Shift의 수정 가능 여부(Pinning)를 결정하는 기준이 됩니다. |

## 2. 데이터 흐름 및 관계 (Data Flow & Relationships)

`DemoDataGenerator.java`를 통해 본 데이터 생성 흐름은 다음과 같습니다.

1.  **ScheduleState 생성 (Time Window 설정)**
    *   가장 먼저 `ScheduleState`를 생성하여 일정의 기준을 잡습니다.
    *   **의미:** "과거(Historic)", "배포됨(Published)", "초안(Draft)" 구간을 정의합니다.
    *   이 상태 정보는 나중에 `Shift`가 수정 가능한지 판단하는 기준이 됩니다.

2.  **Employee 생성 (Resource Pool)**
    *   `Employee` 객체들을 생성합니다.
    *   각 직원은 `SkillSet`(의사, 간호사 등)을 가집니다. 이는 `Shift`의 `requiredSkill`과 매칭되어 **Hard Constraint**(필수 조건)로 작용합니다.

3.  **Availability 생성 (Constraint Data)**
    *   각 직원에 대해 날짜별 선호도(`DESIRED`, `UNDESIRED`, `UNAVAILABLE`)를 생성합니다.
    *   이 데이터는 Solver가 `Shift`에 직원을 배정할 때 선호도(Soft Constraint)나 근무 불가(Hard Constraint)를 계산하는 데 사용됩니다.

4.  **Shift 생성 (The Problem)**
    *   특정 날짜, 시간, 장소, **필요 기술(`requiredSkill`)**을 가진 `Shift`를 생성합니다.
    *   초기 생성 시 `Shift`의 `employee` 필드는 `null`이거나 임의로 설정될 수 있으며, Solver가 최적화를 통해 이 필드를 결정합니다.

## 3. 핵심 필드 및 어노테이션 분석

### `Shift` (Planning Entity)
*   **`@PlanningEntity(pinningFilter = ShiftPinningFilter.class)`**: 이 클래스가 최적화 대상임을 선언합니다. `pinningFilter`는 특정 조건에서 이 객체를 **고정(수정 불가)** 상태로 만듭니다.
*   **`@PlanningVariable Employee employee`**: **핵심 변수**입니다. Solver는 이 필드에 알맞은 `Employee` 객체를 찾아 대입하려고 시도합니다.
*   `LocalDateTime start, end`: 근무 시간 정보로, 근무 시간 중복 여부 등을 판단하는 데 사용됩니다.
*   `String requiredSkill`: 제약 조건입니다. 배정된 `Employee`는 반드시 이 스킬을 가지고 있어야 합니다.

### `EmployeeSchedule` (Planning Solution)
*   **`@ValueRangeProvider List<Employee> employeeList`**: Solver에게 "이 리스트 안에 있는 직원들 중에서만 골라서 Shift에 배정하라"고 알려줍니다.
*   `@ProblemFactCollectionProperty`: `availabilityList`와 `scheduleState`가 점수 계산에 필요한 참고 데이터임을 명시합니다.

### `Availability`
*   `@PlanningId`: 데이터베이스 ID와 별개로, Solver가 객체를 식별하기 위한 ID입니다.
*   관계: `Employee` (N) : `Availability` (M) 관계를 형성하여 날짜별 제약을 정의합니다.

## 4. Pinning 및 상태 관리 (ScheduleState 설정 가이드)

이 모델은 `ScheduleState`와 `ShiftPinningFilter`를 통해 **연속적인 일정 관리(Continuous Planning)**를 구현합니다. `ScheduleState`는 시간의 흐름에 따라 일정을 세 가지 구간으로 구분합니다.

### 구간 정의
1.  **Historic (과거):** 이미 지나간 날짜. 수정 불가.
2.  **Published (배포됨):** 직원들에게 이미 공지된 일정. 수정 불가(Pinning 됨).
3.  **Draft (초안):** 현재 계획 중인 미래의 일정. Solver가 자유롭게 수정(최적화) 가능.

### 필드 설정 가이드
`ScheduleState`의 필드 값들은 이 구간들의 경계를 정의합니다.

*   **`lastHistoricDate` (마지막 과거 날짜)**
    *   **의미:** "여기까지는 이미 완전히 끝난 과거입니다."
    *   **영향:** 이 날짜 **이전(포함)**의 모든 `Shift`는 `isHistoric`으로 간주되어 절대 변경되지 않습니다.

*   **`firstDraftDate` (첫 번째 초안 날짜)**
    *   **의미:** "여기서부터는 아직 확정되지 않은 미래의 계획(초안)입니다." (가장 중요한 설정값)
    *   **영향:** 이 날짜 **이후(포함)**의 모든 `Shift`는 `isDraft`로 간주되어, Solver가 최적화를 수행할 수 있습니다.
    *   **Published 구간 자동 생성:** `lastHistoricDate` 다음 날부터 `firstDraftDate` 전날까지가 자동으로 `Published` 구간이 됩니다.

*   **`publishLength` (배포 주기)**
    *   **의미:** 한 번에 며칠 치 일정을 확정하여 배포하는지 나타냅니다.
    *   **영향:** `publish()` 동작 시 `firstDraftDate`를 이 기간만큼 미래로 이동시켜, 현재의 초안을 배포된 상태로 전환합니다.

### Pinning 메커니즘 (`ShiftPinningFilter`)
```java
public boolean accept(EmployeeSchedule employeeSchedule, Shift shift) {
    ScheduleState scheduleState = employeeSchedule.getScheduleState();
    // Shift가 Draft(초안) 기간에 있지 않으면 -> True 반환 -> Pinning(고정) 됨
    return !scheduleState.isDraft(shift); 
}
```
*   **결과:** Solver는 `Draft` 구간이 아닌(이미 배포되었거나 과거인) `Shift`는 건드리지 않고, 오직 미래의 `Draft` 구간에 있는 `Shift`만 최적화합니다.

## 5. 데이터 구성 및 최적화 전략 (Data Composition & Optimization Strategy)

### 과거 데이터(Historic Data) 포함의 중요성
최적화 대상이 아닌 과거 데이터(예: 2025년 12월)라도 `EmployeeSchedule`의 `shiftList`에 반드시 포함되어야 합니다.

1.  **제약 조건의 연속성 (Constraint Continuity):**
    *   1월 1일의 일정을 계산할 때, 12월 31일의 근무 기록이 필요합니다.
    *   예: "최소 12시간 휴식" 제약을 지키기 위해 전날 야간 근무 여부를 확인해야 합니다.
2.  **문맥(Context) 제공:**
    *   주간/월간 근무 시간 합계를 계산할 때, 기간의 경계에 걸쳐 있는 근무 시간을 정확히 합산하기 위함입니다.

### Pinning과 성능 (Performance Implications)
`ShiftPinningFilter`에 의해 고정된(Pinned) 객체가 많을 경우의 성능 영향은 다음과 같습니다.

*   **탐색(Search) 단계: 영향 없음**
    *   Solver는 Pinned Entity를 변경 대상에서 **완전히 배제**합니다.
    *   따라서 과거 데이터가 포함되어 있어도 최적화(Move) 시도 횟수나 탐색 공간을 낭비하지 않습니다.
*   **점수 계산(Score Calculation) 단계: 영향 있음**
    *   Solver가 점수를 계산할 때는 제약 조건 확인을 위해 전체 리스트(고정된 데이터 포함)를 참조해야 할 수 있습니다.
    *   고정된 데이터가 너무 많으면(예: 수년 치) 매 점수 계산마다 이를 조회하느라 속도가 느려질 수 있습니다.

### 권장 데이터 로딩 전략
*   **Buffer 로딩:** 전체 과거 데이터를 로드하지 말고, 제약 조건 판단에 필요한 **최소한의 기간(Buffer)**만 로드합니다.
    *   **권장:** 가장 긴 제약 조건의 기간(Time Window) + α (예: 지난 14일 ~ 1달).
    *   **예시:** 2026년 1월 최적화를 수행할 때, 2025년 1월부터의 모든 데이터를 가져오는 대신 2025년 12월 15일~31일 데이터만 로드하여 문맥을 제공합니다.