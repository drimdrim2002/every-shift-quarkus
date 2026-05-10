# Night Hard Constraint 4연속 전환 + 3연속 Night Soft 최소화 계획

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:subagent-driven-development`(권장) 또는 `superpowers:executing-plans`를 사용해 Task 단위로 실행한다. 각 Step은 체크박스(`- [ ]`)로 추적한다.

**작성일:** 2026-05-10  
**최종 목표:**
- Night 하드 제약을 `3연속 금지`에서 `4연속 금지`로 전환한다.
- Soft score에 `3연속 Night 최소화` 제약을 신규 추가한다.
- Soft 우선순위는 다음을 따른다: `undesired > 3연속 Night 최소화 > fairness > desired`.
- 기존 Night 휴식 soft 제약(48h/32h)은 유지한다.

**아키텍처 요약:**
- 하드 제약은 `NightHardConstraintValidator`와 `EmployeeSchedulingConstraintProvider`가 함께 보장한다.
- Soft 제약 우선순위는 `BendableScore` soft index 순서로 표현한다.
- 저장/API 점수 매핑(`JobExecutionService`)은 soft index 변경에 맞춰 동기화한다.

**Tech Stack:** Java 21, Quarkus 3.15.1, Maven, OptaPlanner 10 Constraint Streams, JUnit 5.

---

## 1) Deep-Interview로 확정된 요구사항

### 결정사항

- 하드 규칙:
  - `연속 3일 Night` 허용
  - `연속 4일 Night`부터 하드 위반
- Soft 우선순위(비하드):
  - `undesired`가 `3연속 Night 최소화`보다 우선
  - `3연속 Night 최소화`는 `fairness`보다 우선
  - `fairness`는 `desired`보다 우선
- 기존 48h/32h Night 휴식 soft 제약은 유지

### 열린 질문(현재 없음)

- 없음. 구현 중 충돌이 생기면 즉시 본 문서에 추가한다.

---

## 2) 범위 / 제외 범위

### 포함 범위

- `4연속 Night 하드 위반`으로 정책 전환
- `3연속 Night soft penalty` 신규 도입
- soft index 재정렬 및 연계 저장 필드 동기화
- 단위/통합 테스트 기대값 갱신
- 제약 분석 문서 업데이트

### 제외 범위

- Night 휴식 제약(48h/32h)의 정책 자체 변경
- API 응답 필드명(외부 계약) 변경
- Solver 알고리즘(탐색 전략, 종료 정책) 구조 개편

---

## 3) Soft Score 우선순위 모델(최종)

하드 1레벨은 유지하고 soft 레벨은 6개로 확장한다.

```text
Hard[0] 하드 제약
Soft[0] 2연속 Night 후 48시간 휴식 부족분 (기존)
Soft[1] Night 후 Day 32시간 휴식 부족분 (기존)
Soft[2] UNDESIRED(OFF 기피일 위반) 패널티
Soft[3] 3연속 Night 최소화 패널티 (신규)
Soft[4] 공정성(fairness) 패널티
Soft[5] DESIRED(희망일) 보상
```

핵심 해석:
- business 우선순위는 `Soft[2] > Soft[3] > Soft[4] > Soft[5]`.
- 4연속 Night는 하드에서 차단되므로, soft의 3연속 패널티는 “허용 가능한 해 중 더 나은 해 선택” 용도로 동작한다.

---

## 4) 영향 파일 및 책임

- Modify: `src/main/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProvider.java`
  - 하드 4연속 제약 구현
  - soft 3연속 Night 최소화 제약 추가
  - `SOFT_LEVELS` 및 soft index 상수 재배치
- Modify: `src/main/java/org/acme/solver/validation/NightHardConstraintValidator.java`
  - 하드 검증 임계치 `>=4`로 조정
- Modify: `src/main/java/org/acme/service/JobExecutionService.java`
  - 저장용 score index(`undesired/fair/desired`)를 새 soft index로 이동
  - 필요 시 `threeConsecutiveNightSoftScore` 저장 필드 추가 여부 결정
- Modify tests:
  - `src/test/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProviderTest.java`
  - `src/test/java/org/acme/solver/validation/NightHardConstraintValidatorTest.java`
  - `src/test/java/org/acme/solver/SolverRunnerTest.java`
  - `src/test/java/org/acme/service/JobExecutionServiceConfigTest.java`
  - 기타 `BendableScore` shape 의존 테스트
- Modify docs:
  - `docs/CONSTRAINT_PROVIDER_ANALYSIS.md`
  - 본 계획 문서(현재 파일)

---

## 5) 구현 전략

- 하드/소프트를 동시에 다루되, 검증 실패 원인을 분리하기 위해 순차 적용한다.
- `3연속 Night soft`는 Constraint Streams에서 `+1일`, `+2일` 연쇄 매칭으로 탐지한다.
- 페널티 단위는 "3연속 구간 1건당 1 페널티"를 기본으로 한다.
- 5연속 Night가 가능해도(실제론 4연속 하드에서 차단) soft 중첩 윈도우 계산이 일관되도록 테스트를 정의한다.

---

## 6) Task Plan (TDD 체크리스트)

## Task 1: 하드 제약 4연속 전환

**Files:**
- Modify: `src/test/java/org/acme/solver/validation/NightHardConstraintValidatorTest.java`
- Modify: `src/main/java/org/acme/solver/validation/NightHardConstraintValidator.java`
- Modify: `src/test/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProviderTest.java`
- Modify: `src/main/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProvider.java`

- [x] Step 1. validator/constraint 테스트를 3연속 허용, 4연속 위반 기준으로 먼저 수정
- [x] Step 2. `./mvnw test -Dtest=NightHardConstraintValidatorTest,EmployeeSchedulingConstraintProviderTest` 실행해 실패 확인
- [x] Step 3. validator 임계치와 constraint hard 탐지 로직을 4연속 기준으로 구현
- [x] Step 4. 동일 테스트 재실행 및 통과 확인

## Task 2: soft 3연속 Night 최소화 제약 추가

**Files:**
- Modify: `src/test/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProviderTest.java`
- Modify: `src/main/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProvider.java`

- [x] Step 1. 신규 테스트 작성
  - 3연속 Night 1구간이면 `Soft[3]`에 `-1`
  - 3연속이 없으면 `Soft[3] = 0`
  - 4연속(또는 그 이상) 시 중첩 3윈도우 페널티 기대값 명시
- [x] Step 2. focused test 실행으로 실패 확인
- [x] Step 3. `minimizeThreeConsecutiveNightShifts`(가칭) soft constraint 구현
- [x] Step 4. focused test 재실행 및 통과 확인

## Task 3: soft 인덱스 재배치 및 연계 로직 동기화

**Files:**
- Modify: `src/main/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProvider.java`
- Modify: `src/main/java/org/acme/service/JobExecutionService.java`
- Modify: `src/test/java/org/acme/service/JobExecutionServiceConfigTest.java`
- Modify: `src/main/resources/application.properties`

- [x] Step 1. `SOFT_LEVELS`를 `6`으로 확장
- [x] Step 2. soft index를 `[night48, night32, undesired, 3consecutiveNight, fair, desired]` 순으로 재정의
- [x] Step 3. `JobExecutionService` 매핑 조정
  - `undesiredSoftScore -> softScore(2)`
  - `fairSoftScore -> softScore(4)`
  - `desiredSoftScore -> softScore(5)`
- [x] Step 4. termination score shape를 6레벨로 조정
  - 예: `solver.termination.best-score-limit=[0]hard/[0/0/0/0/0/0]soft`
- [x] Step 5. 관련 테스트 갱신/통과

## Task 4: 통합 검증 및 문서 동기화

**Files:**
- Modify: `src/test/java/org/acme/solver/SolverRunnerTest.java`
- Modify: `docs/CONSTRAINT_PROVIDER_ANALYSIS.md`

- [x] Step 1. `SolverRunnerTest`의 hard/soft 단언을 새 index 및 정책 기준으로 갱신
- [x] Step 2. 제약 문서에서 하드/소프트 규칙명과 우선순위를 동일하게 표기
- [x] Step 3. 전체 테스트 실행: `./mvnw test`

---

## 7) 검증 명령

최소 검증:

```bash
./mvnw test -Dtest=NightHardConstraintValidatorTest
./mvnw test -Dtest=EmployeeSchedulingConstraintProviderTest
./mvnw test -Dtest=JobExecutionServiceConfigTest
./mvnw test -Dtest=SolverRunnerTest
```

권장 회귀:

```bash
./mvnw test
```

점검용 검색:

```bash
rg -n "SOFT_LEVELS|softScore\(|BendableScore\.of|best-score-limit|noThreeConsecutiveNightShifts|fourConsecutive|threeConsecutive" src/main src/test docs
```

---

## 8) 완료 기준 (Acceptance Criteria)

- 3연속 Night는 하드 위반이 아니다.
- 4연속 Night는 하드 위반이다(validator + solver hard penalty 일치).
- 3연속 Night가 발생하면 soft에서 패널티가 누적된다.
- soft business 우선순위가 `undesired > 3연속 Night 최소화 > fairness > desired`로 구현/테스트에서 일치한다.
- 기존 Night 휴식 soft 제약(48h/32h)은 유지되며 index 충돌이 없다.
- `JobExecutionService` 저장 점수 매핑이 새 soft index와 일치한다.
- `./mvnw test`가 성공한다.

---

## 9) 리스크 및 대응

- 리스크: 3연속 soft와 4연속 hard 동시 적용 시 중복 해석 혼선
- 대응: 테스트에서 하드 위반 여부와 soft 페널티를 분리 검증

- 리스크: soft index 변경으로 저장/응답 score 불일치
- 대응: `JobExecutionServiceConfigTest`에서 인덱스 고정 검증

- 리스크: 기존 5-level fixture 누락으로 숨은 회귀
- 대응: `BendableScore` 생성/파싱 문자열 전수 검색 후 수정

---

## 10) 롤백 전략

- 정책 롤백 필요 시 다음 두 축을 함께 되돌린다.
  - 하드 임계치: 4연속 -> 3연속
  - soft 신규 제약: 3연속 최소화 제거 및 score shape 원복
- 롤백 시 테스트 기대값/문서/저장 index를 동시에 원복한다.
