# Quarkus Fast-Jar Size Reduction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `target/quarkus-app` fast-jar 배포 크기를 줄이기 위해 현재 사용하지 않는 Hibernate ORM, Panache, H2 런타임 의존성을 제거한다.

**Architecture:** 애플리케이션은 Quarkus REST, Jackson, Firestore, Cloud Run Job 호출, OptaPlanner 기반 solver 구조를 그대로 유지한다. 도메인 모델은 JPA entity가 아니라 OptaPlanner planning annotation과 Jackson 직렬화에 필요한 plain Java model로 남긴다. production/test 코드에서 참조되지 않는 Panache repository는 삭제한다.

**Tech Stack:** Java 21, Quarkus 3.15.1, Maven Wrapper, OptaPlanner, Jackson, Google Cloud Firestore/Cloud Run APIs.

---

## 범위

이번 계획은 fast-jar 크기 감소를 위한 1차 최적화만 다룬다.

- 제거 대상: Hibernate ORM, Hibernate ORM Panache, JDBC H2, JPA annotation, Panache repository.
- 유지 대상: Quarkus REST/Jackson, OptaPlanner, Firestore, Cloud Tasks, Cloud Run Job invocation, Jib 설정.
- 제외 대상: Google Cloud client dependency 최적화, native image, Docker base image 변경, solver 알고리즘 변경.

## 현재 근거

현재 측정값은 다음 수준이다.

- `target/quarkus-app`: 약 `100M`
- `target/quarkus-app/lib`: 약 `99M`
- `target/quarkus-app/app`: 약 `140K`

첫 최적화 대상은 사용하지 않는 persistence stack이다.

- `quarkus-hibernate-orm`
- `quarkus-hibernate-orm-panache`
- `quarkus-jdbc-h2`
- `src/main/java/org/acme/solver/persistence/*Repository.java`
- `Employee`, `Shift`, `Availability`, `ScheduleState`의 `jakarta.persistence` annotation

예상 감소량은 transitive dependency pruning 결과에 따라 약 `15M`에서 `21M`이다.

## 파일 구조

수정/삭제할 파일과 책임은 다음과 같다.

- 수정: `pom.xml`
  - Quarkus extension dependency 목록을 관리한다.
  - 사용하지 않는 persistence extension 3개만 제거한다.
- 수정: `src/main/resources/application.properties`
  - 런타임 설정을 관리한다.
  - H2 datasource 설정 2개만 제거한다.
- 수정: `src/main/java/org/acme/model/Employee.java`
  - 직원 planning id, 이름, 보유 skill, 근무 가능 shift set을 담는 plain Java model이다.
  - `@PlanningId`는 유지하고 JPA annotation만 제거한다.
- 수정: `src/main/java/org/acme/model/Shift.java`
  - OptaPlanner planning entity와 shift assignment 상태를 담는다.
  - `@PlanningEntity`, `@PlanningId`, `@PlanningVariable`, Jackson `@JsonProperty`는 유지하고 JPA annotation만 제거한다.
- 수정: `src/main/java/org/acme/model/Availability.java`
  - 직원별 날짜 availability를 담는 plain Java model이다.
  - `@PlanningId`는 유지하고 JPA annotation만 제거한다.
- 수정: `src/main/java/org/acme/model/ScheduleState.java`
  - publish/draft/historic schedule window 판정 로직을 담는다.
  - Jackson `@JsonIgnore`와 business method는 유지하고 JPA annotation만 제거한다.
- 삭제: `src/main/java/org/acme/solver/persistence/AvailabilityRepository.java`
- 삭제: `src/main/java/org/acme/solver/persistence/EmployeeRepository.java`
- 삭제: `src/main/java/org/acme/solver/persistence/ScheduleStateRepository.java`
- 삭제: `src/main/java/org/acme/solver/persistence/ShiftRepository.java`
  - 위 repository들은 Panache 전용 dead code다. 삭제 전 참조 검색으로 unused 상태를 확인한다.

## 구현 메모

- 이 작업은 behavior change가 아니라 dependency pruning이다. solver, API routing, export, Firestore tracking 동작을 바꾸지 않는다.
- `@PlanningId`와 `@PlanningVariable`은 OptaPlanner에 필요하므로 제거하지 않는다.
- `Shift.end` 필드의 `@Column(name = "endDateTime")`은 H2 예약어 충돌 회피용이므로 JPA 제거와 함께 삭제한다. 필드명과 getter/setter는 그대로 둔다.
- `Availability.id`와 `Shift.id`의 `@GeneratedValue` 삭제 후에도 테스트 데이터가 id를 직접 설정하는 흐름은 유지되어야 한다.
- macOS zsh에서 glob이 매칭되지 않으면 `no matches found`가 날 수 있다. dependency 크기 측정 명령은 `find` 기반 명령을 우선 사용한다.

## Task 1: 기준값과 특성 검증 기록

**파일:**

- 소스 변경 없음.

- [ ] **Step 1: 현재 worktree 확인**

실행:

```bash
git status --short
```

기대 결과: 기존 변경 사항을 기록한다. 관련 없는 변경은 되돌리지 않는다.

- [ ] **Step 2: 제거 전 persistence reference 존재 확인**

실행:

```bash
rg -n "quarkus-hibernate|quarkus-jdbc-h2|quarkus.datasource|PanacheRepository|jakarta.persistence|@Entity|@GeneratedValue|@ManyToOne|@ElementCollection|@Column" pom.xml src/main/resources src/main/java src/test/java
```

기대 결과: `pom.xml`, `application.properties`, `src/main/java/org/acme/model/*`, `src/main/java/org/acme/solver/persistence/*Repository.java`에서 현재 제거 대상 reference가 발견된다.

- [ ] **Step 3: 현재 fast-jar 크기 기록**

실행:

```bash
du -sh target/quarkus-app target/quarkus-app/lib target/quarkus-app/app
```

기대 결과: 현재 값이 `target/quarkus-app` 약 `100M`, `target/quarkus-app/lib` 약 `99M`, `target/quarkus-app/app` 약 `140K` 수준이다. `target/quarkus-app`이 없으면 먼저 Task 5의 package command를 실행해 baseline artifact를 만든 뒤 다시 측정한다.

- [ ] **Step 4: 제거 대상 persistence dependency 증거 기록**

실행:

```bash
find target/quarkus-app/lib/main -type f \( -iname '*hibernate*' -o -iname '*agroal*' -o -iname '*h2*' -o -iname '*byte-buddy*' -o -iname '*narayana*' -o -iname '*jakarta.persistence*' \) -print
```

기대 결과: persistence stack 관련 jar 목록이 출력된다.

- [ ] **Step 5: 제거 대상 dependency 총량 기록**

실행:

```bash
find target/quarkus-app/lib/main -type f \( -iname '*hibernate*' -o -iname '*agroal*' -o -iname '*h2*' -o -iname '*byte-buddy*' -o -iname '*narayana*' -o -iname '*jakarta.persistence*' \) -exec du -ch {} + | tail -n 1
```

기대 결과: total이 약 `15M`에서 `21M` 범위다. 정확한 값은 plan 실행 기록에 남긴다.

- [ ] **Step 6: 수정 전 characterization test 실행**

실행:

```bash
./mvnw test
```

기대 결과: 현재 기준 테스트가 통과한다. sandbox가 Quarkus test HTTP socket binding을 막아 `java.net.SocketException: Operation not permitted`가 발생하면 같은 명령을 escalated permission으로 재실행한다.

## Task 2: persistence dependency 제거

**파일:**

- 수정: `pom.xml`
- 수정: `src/main/resources/application.properties`

- [ ] **Step 1: 사용하지 않는 Maven dependency 제거**

`pom.xml`에서 정확히 다음 dependency block만 제거한다.

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-hibernate-orm</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-hibernate-orm-panache</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-jdbc-h2</artifactId>
</dependency>
```

유지 대상: `quarkus-arc`, `quarkus-rest-jackson`, `quarkus-container-image-jib`, OptaPlanner, Google Cloud, test dependency는 변경하지 않는다.

- [ ] **Step 2: 사용하지 않는 datasource 설정 제거**

`src/main/resources/application.properties`에서 정확히 다음 설정만 제거한다.

```properties
# H2? ??? DB? ?? (?? ???)
quarkus.datasource.db-kind=h2
quarkus.datasource.jdbc.url=jdbc:h2:mem:testdb
```

`gcp.*`, `app.*`, solver, Firestore, CORS, container-image 설정은 변경하지 않는다.

- [ ] **Step 3: dependency/config reference 제거 확인**

실행:

```bash
rg -n "quarkus-hibernate|quarkus-jdbc-h2|quarkus.datasource" pom.xml src/main/resources
```

기대 결과: 결과가 없어야 한다.

- [ ] **Step 4: dependency 제거 경계 compile 확인**

실행:

```bash
./mvnw -DskipTests compile
```

기대 결과: model/repository 코드가 아직 JPA/Panache를 import하므로 compile이 실패할 수 있다. 실패한다면 원인은 관련 없는 애플리케이션 코드가 아니라 `jakarta.persistence` 또는 `PanacheRepository`여야 한다. 이후 Task 3을 진행한다.

## Task 3: domain model에서 JPA 제거

**파일:**

- 수정: `src/main/java/org/acme/model/Employee.java`
- 수정: `src/main/java/org/acme/model/Shift.java`
- 수정: `src/main/java/org/acme/model/Availability.java`
- 수정: `src/main/java/org/acme/model/ScheduleState.java`

- [ ] **Step 1: `Employee`에서 JPA import와 annotation 제거**

`src/main/java/org/acme/model/Employee.java`에서 제거:

```java
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
```

제거:

```java
@Entity
@Id
@ElementCollection(fetch = FetchType.EAGER)
```

유지:

```java
import org.optaplanner.core.api.domain.lookup.PlanningId;

@PlanningId
String id;
```

기대 결과: `skillSet`과 `availableShift`는 `Set<String>` field로 남고 기존 constructor/getter/setter는 변경되지 않는다.

- [ ] **Step 2: `Shift`에서 JPA import와 annotation 제거**

`src/main/java/org/acme/model/Shift.java`에서 제거:

```java
import jakarta.persistence.*;
```

제거:

```java
@Entity
@Id
@GeneratedValue
@Column(name = "endDateTime") // "end" clashes with H2 syntax.
@ManyToOne
```

유지:

```java
import com.fasterxml.jackson.annotation.JsonProperty;
import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.lookup.PlanningId;
import org.optaplanner.core.api.domain.variable.PlanningVariable;

@PlanningEntity(pinningFilter = ShiftPinningFilter.class)
@PlanningId
@PlanningVariable
```

기대 결과: `Long id`, `LocalDateTime end`, `Employee employee` field는 남는다. JPA metadata만 사라진다.

- [ ] **Step 3: `Availability`에서 JPA import와 annotation 제거**

`src/main/java/org/acme/model/Availability.java`에서 제거:

```java
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
```

제거:

```java
@Entity
@Id
@GeneratedValue
@ManyToOne
```

유지:

```java
import org.optaplanner.core.api.domain.lookup.PlanningId;

@PlanningId
Long id;
Employee employee;
```

기대 결과: constructor, getter, setter, `toString()`은 변경되지 않는다.

- [ ] **Step 4: `ScheduleState`에서 JPA import와 annotation 제거**

`src/main/java/org/acme/model/ScheduleState.java`에서 제거:

```java
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
```

제거:

```java
@Entity
@Id
```

유지:

```java
import com.fasterxml.jackson.annotation.JsonIgnore;
```

기대 결과: 모든 `@JsonIgnore` annotation과 schedule window business method는 변경되지 않는다.

- [ ] **Step 5: main code에 JPA import가 남지 않았는지 확인**

실행:

```bash
rg -n "jakarta.persistence|@Entity|@Id|@GeneratedValue|@ManyToOne|@ElementCollection|@Column" src/main/java
```

기대 결과: 결과가 없어야 한다.

- [ ] **Step 6: model conversion compile 확인**

실행:

```bash
./mvnw -DskipTests compile
```

기대 결과: repository 파일이 아직 `PanacheRepository`를 import하기 때문에 compile이 실패할 수 있다. 실패 원인은 이 범위에 한정되어야 한다. OptaPlanner/Jackson/model behavior 관련 실패가 있으면 repository 삭제 전에 model conversion을 먼저 수정한다.

## Task 4: 사용하지 않는 Panache repository 제거

**파일:**

- 삭제: `src/main/java/org/acme/solver/persistence/AvailabilityRepository.java`
- 삭제: `src/main/java/org/acme/solver/persistence/EmployeeRepository.java`
- 삭제: `src/main/java/org/acme/solver/persistence/ScheduleStateRepository.java`
- 삭제: `src/main/java/org/acme/solver/persistence/ShiftRepository.java`

- [ ] **Step 1: repository가 정의부 외부에서 사용되지 않는지 확인**

실행:

```bash
rg -n "AvailabilityRepository|EmployeeRepository|ScheduleStateRepository|ShiftRepository|PanacheRepository|org.acme.solver.persistence" src/main/java src/test/java
```

기대 결과: 네 repository class 정의와 각 파일의 `PanacheRepository` import만 발견되어야 한다. production/test의 다른 파일이 이 class 중 하나를 참조하면 중단하고 caller를 함께 수정하거나 별도 persistence-removal task로 분리한다.

- [ ] **Step 2: 사용하지 않는 repository file 삭제**

제거:

```text
src/main/java/org/acme/solver/persistence/AvailabilityRepository.java
src/main/java/org/acme/solver/persistence/EmployeeRepository.java
src/main/java/org/acme/solver/persistence/ScheduleStateRepository.java
src/main/java/org/acme/solver/persistence/ShiftRepository.java
```

- [ ] **Step 3: Panache reference가 남지 않았는지 확인**

실행:

```bash
rg -n "PanacheRepository|org.acme.solver.persistence|AvailabilityRepository|EmployeeRepository|ScheduleStateRepository|ShiftRepository" src/main/java src/test/java
```

기대 결과: 결과가 없어야 한다.

- [ ] **Step 4: repository 삭제 후 compile**

실행:

```bash
./mvnw -DskipTests compile
```

기대 결과: `BUILD SUCCESS`.

- [ ] **Step 5: dependency와 source cleanup commit**

실행:

```bash
git add pom.xml src/main/resources/application.properties src/main/java/org/acme/model/Employee.java src/main/java/org/acme/model/Shift.java src/main/java/org/acme/model/Availability.java src/main/java/org/acme/model/ScheduleState.java src/main/java/org/acme/solver/persistence
git commit -m "refactor: remove unused persistence stack"
```

기대 결과: commit이 성공한다. session policy상 자동 commit을 하지 않는다면 이 단계는 체크하지 않고 staged/unstaged 상태를 보고한다.

## Task 5: Build와 크기 측정

**파일:**

- `target/` 아래 생성물만 해당.

- [ ] **Step 1: container image push 없이 package build 실행**

실행:

```bash
./mvnw package -DskipTests -Dquarkus.container-image.build=false -Dquarkus.container-image.push=false
```

기대 결과: `BUILD SUCCESS`.

- [ ] **Step 2: 새 fast-jar 크기 측정**

실행:

```bash
du -sh target/quarkus-app target/quarkus-app/lib target/quarkus-app/app
```

기대 결과: `target/quarkus-app`와 `target/quarkus-app/lib`가 Task 1 baseline보다 작아야 한다. 변경 전/후 값을 implementation summary에 기록한다.

- [ ] **Step 3: 제거 대상 jar 부재 확인**

실행:

```bash
find target/quarkus-app/lib -type f \( -iname '*hibernate*' -o -iname '*h2*' -o -iname '*byte-buddy*' -o -iname '*agroal*' -o -iname '*narayana*' -o -iname '*jakarta.persistence*' \) -print
```

기대 결과: 다른 dependency가 정당하게 다시 끌어오지 않는 한 persistence-stack jar가 남지 않아야 한다. jar가 남아 있다면 size reduction 완료를 주장하기 전에 어떤 dependency가 소유하는지 확인한다.

- [ ] **Step 4: 제거한 extension의 Maven dependency tree 확인**

실행:

```bash
./mvnw dependency:tree -Dincludes=io.quarkus:quarkus-hibernate-orm,io.quarkus:quarkus-hibernate-orm-panache,io.quarkus:quarkus-jdbc-h2
```

기대 결과: 제거한 Quarkus extension으로 이어지는 dependency path가 없어야 한다.

## Task 6: 회귀 테스트

**파일:**

- 테스트/빌드 생성물만 해당.

- [ ] **Step 1: 전체 test suite 실행**

실행:

```bash
./mvnw test
```

기대 결과: 기존 테스트가 모두 통과한다. 이전 기준 test count는 다음과 같다.

```text
Tests run: 94, Failures: 0, Errors: 0
```

관련 없는 작업에서 test가 추가/삭제되어 test count가 바뀌면 차이를 설명한다. sandbox가 Quarkus test HTTP socket binding을 막아 `java.net.SocketException: Operation not permitted`가 발생하면 같은 명령을 escalated permission으로 재실행한다.

- [ ] **Step 2: targeted behavior check 실행**

실행:

```bash
./mvnw test -Dtest=SolverRunnerTest,SolverRunnerTerminationPolicyTest,DtoConverterTest,DtoConverterIdTest,JsonScheduleExporterTest,EmployeeViewExporterTest,SolverResourceRoutingPolicyTest,CloudRunJobInvokerTest,JobExecutionServiceConfigTest
```

기대 결과: solver, DTO conversion, export, API routing, Cloud Run invocation configuration 대상 테스트가 통과한다.

- [ ] **Step 3: 제거한 source reference 재확인**

실행:

```bash
rg -n "quarkus-hibernate|quarkus-jdbc-h2|quarkus.datasource|PanacheRepository|jakarta.persistence|@Entity|@GeneratedValue|@ManyToOne|@ElementCollection|@Column" pom.xml src/main/resources src/main/java src/test/java
```

기대 결과: 결과가 없어야 한다.

- [ ] **Step 4: 의도하지 않은 source 변경 확인**

실행:

```bash
git status --short
```

기대 결과: 의도한 수정/삭제만 존재하거나, commit step을 실행했다면 clean worktree여야 한다.

## 승인 기준

- `./mvnw package -DskipTests -Dquarkus.container-image.build=false -Dquarkus.container-image.push=false`가 성공한다.
- `./mvnw test`가 failure/error 없이 성공한다.
- Task 6 Step 2의 targeted behavior check가 통과한다.
- `target/quarkus-app`와 `target/quarkus-app/lib`가 Task 1 baseline보다 작다.
- `pom.xml` 또는 Maven dependency tree에 `quarkus-hibernate-orm`, `quarkus-hibernate-orm-panache`, `quarkus-jdbc-h2` dependency가 남지 않는다.
- `src/main/resources/application.properties`에 `quarkus.datasource.*` H2 configuration이 남지 않는다.
- `src/main/java` 또는 `src/test/java`에 `jakarta.persistence`나 `PanacheRepository` reference가 남지 않는다.
- REST API, solver execution, JSON export, Firestore job tracking behavior가 변경되지 않는다.

## 복구 계획

compile/test/package 실패가 이 범위 안에서 해결되지 않으면 다음 절차로 복구한다.

- 이전 commit에서 `pom.xml`의 persistence dependency를 복구한다.
- `src/main/resources/application.properties`의 H2 datasource property 2개를 복구한다.
- `Employee`, `Shift`, `Availability`, `ScheduleState`의 JPA import/annotation을 복구한다.
- `src/main/java/org/acme/solver/persistence` 아래 파일 4개를 복구한다.
- 다시 실행한다.

```bash
./mvnw test
```

기대 결과: repository가 변경 전 동작으로 돌아간다.

## 리뷰 인계

merge 또는 deployment 전에 reviewer는 다음을 확인한다.

- diff가 dependency/config/model/repository cleanup만 포함한다.
- solver algorithm, API contract, Firestore schema, Cloud Run Job setting, export format이 변경되지 않았다.
- test에 필요한 `@PlanningId`, `@PlanningEntity`, `@PlanningVariable`, Jackson annotation, constructor, getter, setter가 유지되어 있다.
- implementation summary에 변경 전/후 size number가 포함되어 있다.
- `find target/quarkus-app/lib ...` 결과로 남은 persistence-like jar가 있다면 dependency evidence와 함께 설명되어 있다.

## 가정

- Panache repository는 production/test 코드가 참조하지 않으므로 dead code다.
- JPA annotation은 OptaPlanner에 필요하지 않으며, OptaPlanner annotation은 유지한다.
- test와 DTO conversion path가 persistence metadata 제거에 대한 characterization coverage를 충분히 제공한다.
- 이 계획은 Google Cloud Firestore, Cloud Tasks, Cloud Run Job dependency를 변경하지 않는다. 해당 영역은 별도 2차 최적화 pass 후보이다.
- 이 계획은 `application.properties`의 `quarkus.container-image.build=true`, `quarkus.container-image.push=true` default를 변경하지 않는다. local JAR size 검증 시에만 build command에서 override한다.
