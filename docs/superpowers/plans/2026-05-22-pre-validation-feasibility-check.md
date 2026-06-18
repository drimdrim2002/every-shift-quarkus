# 사전 정합성(Feasibility) 검증 및 테스트 복구 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로컬 및 운영 환경에서 솔버 구동 전 입력 데이터(`PlanningRequest`) 수준에서 발생할 수 있는 비즈니스적 Feasibility 모순을 1ms 내에 감지하여 차단하고, 최근 데이터셋 갱신으로 인해 깨진 테스트 케이스(`DtoConverterTest`, `RequestValidatorTest`)를 완벽히 통과시켜 전체 빌드 성공(BUILD SUCCESS)을 실현합니다.

**Architecture:** 
1. `RequestValidator.java`의 필수 스킬 가용성 검증 시, `skill_set`뿐만 아니라 `available_shifts` 필드(시프트 ID 및 코드 매칭)를 종합적으로 대조하여 실무 도메인 규칙을 준수합니다.
2. 검증 단위를 날짜별/스킬별에서 날짜별/시프트ID별 요구 인원 대 가용 직원 수 검증으로 리팩토링합니다.
3. 데이터셋 교체로 인해 깨진 `DtoConverterTest`를 구동하기 위해 정밀 검증용 경량 JSON 데이터셋(`fairness_test.json`)을 독립하여 신규 생성합니다.

**Tech Stack:** Java 21, Quarkus 3.15.1, JUnit 5, Jackson (JSR310)

---

### Task 1: 테스트용 경량 fairness 데이터셋 생성 및 DtoConverterTest 경로 변경

**Files:**
- Create: `src/test/resources/json/fairness_test.json`
- Modify: `src/test/java/org/acme/solver/util/DtoConverterTest.java`

- [x] **Step 1: fairness_test.json 데이터셋 생성**
  과거 `E1`, `E2` 직원과 구체적인 연간 통계가 명시된 경량 fairness 데이터셋을 생성합니다.

  파일 경로: `src/test/resources/json/fairness_test.json`
  ```json
  {
    "organization": {
      "id": "ORG1",
      "name": "Fairness Test Hospital",
      "type": "hospital",
      "shifts": [
        {
          "id": "SHIFT_D",
          "code": "D",
          "name": "Day",
          "start_time": "08:00:00",
          "end_time": "16:00:00"
        },
        {
          "id": "SHIFT_E",
          "code": "E",
          "name": "Evening",
          "start_time": "16:00:00",
          "end_time": "00:00:00"
        },
        {
          "id": "SHIFT_N",
          "code": "N",
          "name": "Night",
          "start_time": "00:00:00",
          "end_time": "08:00:00"
        }
      ],
      "lastHistoricalDate": "2025-12-04",
      "firstDraftDate": "2025-12-05",
      "publishLength": 0,
      "draftLength": 5
    },
    "employees": [
      {
        "employee_id": "E1",
        "name": "Employee One",
        "available_shifts": [
          "D",
          "E",
          "N"
        ],
        "skill_set": [
          "ALL"
        ]
      },
      {
        "employee_id": "E2",
        "name": "Employee Two",
        "available_shifts": [
          "D",
          "E",
          "N"
        ],
        "skill_set": [
          "ALL"
        ]
      }
    ],
    "history": [
      {
        "employee_id": "E1",
        "shift_id": "SHIFT_N",
        "date": "2025-12-04",
        "is_locked": true
      }
    ],
    "undesirable": [],
    "requirements": [
      {
        "shiftId": "SHIFT_N",
        "dayIndex": 0,
        "employeeCount": 1
      },
      {
        "shiftId": "SHIFT_D",
        "dayIndex": 1,
        "employeeCount": 1
      },
      {
        "shiftId": "SHIFT_E",
        "dayIndex": 1,
        "employeeCount": 1
      },
      {
        "shiftId": "SHIFT_N",
        "dayIndex": 1,
        "employeeCount": 1
      },
      {
        "shiftId": "SHIFT_D",
        "dayIndex": 2,
        "employeeCount": 1
      },
      {
        "shiftId": "SHIFT_E",
        "dayIndex": 2,
        "employeeCount": 1
      },
      {
        "shiftId": "SHIFT_N",
        "dayIndex": 2,
        "employeeCount": 1
      },
      {
        "shiftId": "SHIFT_N",
        "dayIndex": 3,
        "employeeCount": 1
      }
    ],
    "publicHolidays": [
      {
        "date": "2025-12-08",
        "dayOfWeek": "MONDAY",
        "dayName": "Monday",
        "kind": "PUBLIC_HOLIDAY"
      }
    ],
    "yearlyEmployeeStats": [
      {
        "employee_id": "E1",
        "nightWorkCount": 7,
        "holidayWorkCount": 4,
        "offRequestCount": 1
      }
    ]
  }
  ```

- [x] **Step 2: DtoConverterTest에서 신규 JSON 데이터셋 로드하도록 변경**
  `src/test/java/org/acme/solver/util/DtoConverterTest.java` 파일을 수정합니다.

  ```java
      @Test
      public void testFairnessInputsPopulateEmployeeStatsAndBurdenScores() throws IOException {
          PlanningRequest request = JsonLoader.load("/json/fairness_test.json", PlanningRequest.class);
  ```

- [x] **Step 3: DtoConverterTest 실행하여 NoSuchElementException 해결 확인**
  Run: `./mvnw test -Dtest=DtoConverterTest#testFairnessInputsPopulateEmployeeStatsAndBurdenScores`
  Expected: PASS 또는 ValidationException (사전 검증 로직 통과 여부에 따름)

- [x] **Step 4: Commit**
  ```bash
  git add src/test/resources/json/fairness_test.json src/test/java/org/acme/solver/util/DtoConverterTest.java
  git commit -m "test: isolate test-specific fairness dataset and update DtoConverterTest"
  ```

---

### Task 2: RequestValidator의 필수 스킬 가용성 검증 로직 보완

**Files:**
- Modify: `src/main/java/org/acme/util/RequestValidator.java`

- [x] **Step 1: RequestValidator의 스킬 검증 로직 보완 및 리팩토링**
  직원의 `availableShifts`에 `shiftId` 또는 `shiftCode`가 포함되어 있을 경우 및 `skillSet`에 시프트 코드가 포함되어 있을 경우를 종합하여 가용 직원을 카운트하도록 리팩토링합니다.

  `src/main/java/org/acme/util/RequestValidator.java` 내 `validateSolverFeasibility` 메서드 리팩토링:
  ```java
          // 1.8 시프트 ID 별 요구 스킬 및 코드 정보 매핑 구성
          Map<String, PlanningRequest.ShiftInfo> shiftInfoById = new HashMap<>();
          if (request.organization().shifts() != null) {
              for (PlanningRequest.ShiftInfo shift : request.organization().shifts()) {
                  shiftInfoById.put(shift.id(), shift);
              }
          }

          // 2. 필수 스킬 및 시프트 가용성 검증 리팩토링
          // 일자별/시프트 ID별 요구 정원 집계
          Map<LocalDate, Map<String, Integer>> dailyShiftReqs = new HashMap<>();
          for (PlanningRequest.RequirementInfo req : request.requirements()) {
              LocalDate targetDate = startDate.plusDays(req.dayIndex());
              dailyShiftReqs.computeIfAbsent(targetDate, k -> new HashMap<>())
                            .merge(req.shiftId(), req.employeeCount(), Integer::sum);
          }

          // 일자별/시프트 ID별 가용 직원 수 검증
          for (Map.Entry<LocalDate, Map<String, Integer>> entry : dailyShiftReqs.entrySet()) {
              LocalDate date = entry.getKey();
              for (Map.Entry<String, Integer> shiftEntry : entry.getValue().entrySet()) {
                  String shiftId = shiftEntry.getKey();
                  int requiredCount = shiftEntry.getValue();

                  PlanningRequest.ShiftInfo shift = shiftInfoById.get(shiftId);
                  if (shift == null) {
                      continue;
                  }

                  String skill = shift.code();

                  // requiredSkill(code)이 null이거나 "ALL"인 경우는 제외하지 않고,
                  // 직원이 해당 시프트를 할당받을 수 있는 모든 조건을 대조
                  long availableEmployees = request.employees().stream()
                      .filter(emp -> 
                          (emp.availableShifts() != null && emp.availableShifts().contains(shiftId)) ||
                          (emp.availableShifts() != null && emp.availableShifts().contains(skill)) ||
                          (emp.skillSet() != null && (emp.skillSet().contains(skill) || emp.skillSet().contains("ALL")))
                      )
                      .count();

                  if (requiredCount > availableEmployees) {
                      feasibilityErrors.add(String.format(
                          "[%s] '%s' 근무를 수행할 수 있는 가용 인원(%d명)이 요구되는 근무 정원(%d명)보다 부족합니다.",
                          date, skill, availableEmployees, requiredCount
                      ));
                  }
              }
          }
  ```

- [x] **Step 2: 전체 RequestValidatorTest 실행하여 성공 검증**
  Run: `./mvnw test -Dtest=RequestValidatorTest`
  Expected: BUILD SUCCESS (모든 6개 테스트 통과)

- [x] **Step 3: Commit**
  ```bash
  git add src/main/java/org/acme/util/RequestValidator.java
  git commit -m "feat: enhance RequestValidator feasibility check with shift capability verification"
  ```

---

### Task 3: 전체 프로젝트 통합 검증 및 빌드 확인

- [/] **Step 1: 전체 단위 및 통합 테스트 실행**
  Run: `./mvnw clean test`
  Expected: BUILD SUCCESS (전체 114개 이상의 테스트가 100% 정상 통과해야 함)

- [ ] **Step 2: 최종 마무리 커밋 또는 푸시 조율**
  수정 사항들이 성공적으로 컴파일되고 테스트 통과했는지 확인한 뒤 작업을 마칩니다.
