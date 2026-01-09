# 데이터 영속성 및 저장 구조 분석

## 1. 영속성 계층 (Persistence Layer)
`org.acme.employeescheduling.persistence` 패키지의 클래스들은 데이터베이스와 상호작용하여 데이터를 관리하는 Repository 역할을 수행합니다.

| 클래스 | 대상 엔티티 | 역할 |
| :--- | :--- | :--- |
| **`AvailabilityRepository`** | `Availability` | 직원 가용성 및 휴무 요청 데이터 관리 |
| **`EmployeeRepository`** | `Employee` | 직원 정보 데이터 관리 |
| **`ScheduleStateRepository`** | `ScheduleState` | 전체 일정 상태 및 기준 날짜 관리 |
| **`ShiftRepository`** | `Shift` | 교대 근무 일정 데이터 관리 |

### 구현 기술: Quarkus Hibernate ORM with Panache
* **PanacheRepository:** 모든 Repository는 `PanacheRepository<Entity>` 인터페이스를 상속받아, 복잡한 코드 없이 엔티티에 대한 CRUD 작업을 수행합니다.
* **CDI Bean:** `@ApplicationScoped` 어노테이션을 통해 애플리케이션 전역에서 주입되어 사용됩니다.

## 2. 데이터베이스 설정 (Database Configuration)
애플리케이션이 실행되는 환경(Profile)에 따라 데이터베이스 저장 방식이 달라집니다.

### 2.1 개발 및 로컬 환경 (Default)
* **DB 종류:** **H2 Database (In-Memory)**
* **설정 위치:** `src/main/resources/application.properties`
* **특징:** 
    * `jdbc:h2:mem` 방식을 사용하여 메모리에 데이터를 저장합니다.
    * `quarkus.hibernate-orm.database.generation=drop-and-create` 설정에 의해 앱 시작 시 테이블을 생성하고, 종료 시 데이터가 휘발됩니다.

### 2.2 클라우드/운영 환경 (OpenShift Native 등)
* **DB 종류:** **PostgreSQL**
* **설정 위치:** `src/main/resources/application.properties` (프로필: `%openshift-native`)
* **특징:**
    * 실제 운영을 위해 영구 저장소인 PostgreSQL을 사용하도록 구성되어 있습니다.
    * `pom.xml`의 `openshift-native` 프로필이 활성화될 때 `quarkus-jdbc-postgresql` 드라이버를 로드합니다.

## 3. 핵심 설정 정보 경로
* **DB 엔진 및 URL:** `src/main/resources/application.properties`
* **DB 드라이버 및 프로필:** `pom.xml`
* **도메인 엔티티:** `src/main/java/org/acme/employeescheduling/domain/`
