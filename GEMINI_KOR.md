# 프로젝트 컨텍스트: every-shift-quarkus

## 1. 프로젝트 개요
이 프로젝트는 **Quarkus** 프레임워크 기반의 근무표(Shift) 관리 및 자동 생성 시스템입니다. 
하나의 애플리케이션 이미지가 환경 변수(`APP_MODE`)에 따라 **REST API 서버** 또는 **Cloud Run Job(백그라운드 작업자)** 으로 동작하는 **이중 모드(Dual-mode)** 아키텍처를 채택하고 있습니다.

## 2. 기술 스택
- **언어:** Java 21
- **프레임워크:** Quarkus 3.15.1
- **빌드 도구:** Maven (Maven Wrapper 포함)
- **컨테이너 빌드:** Jib (`quarkus-container-image-jib`)
- **클라우드 플랫폼:** Google Cloud Platform (Cloud Run Service & Jobs)
- **주요 라이브러리:**
    - `quarkus-rest-jackson`: JSON 처리 및 REST API
    - `google-auth-library-oauth2-http`: Google Cloud API 인증
    - `quarkus-arc`: 의존성 주입 (CDI)

## 3. 아키텍처 및 동작 방식
이 애플리케이션은 `APP_MODE` 환경 변수에 따라 두 가지 역할 중 하나를 수행합니다.

### A. API 모드 (`APP_MODE=API`)
- **역할:** 사용자의 요청을 받아들이고, 무거운 연산 작업(솔버)을 Cloud Run Job으로 위임합니다.
- **진입점:** `org.acme.api.SolverTriggerResource`
- **주요 엔드포인트:**
    - `POST /api/trigger`: Google Cloud API를 호출하여 `hello-world-job`을 실행합니다.

### B. Job 모드 (`APP_MODE=JOB`)
- **역할:** 실제 근무표 생성 알고리즘(솔버)을 실행하는 배치 작업입니다.
- **진입점:** `org.acme.ApplicationMain` (추정, 또는 Quarkus Lifecycle 관찰자) -> `org.acme.solver.SolverRunner`
- **로직:** `SolverRunner`에서 JSON 입력을 받아 메타휴리스틱 알고리즘(현재는 플레이스홀더)을 수행합니다.

## 4. 주요 파일 및 디렉토리
- **`pom.xml`**: 프로젝트 의존성 및 빌드 설정. Jib를 이용한 컨테이너 이미지 빌드 설정이 포함되어 있습니다.
- **`deploy.sh`**: 전체 배포 스크립트.
    1. Maven 패키징 및 이미지 빌드/푸시.
    2. Cloud Run Job (`hello-world-job`) 배포.
    3. Cloud Run Service (`every-shift-api-service`) 배포.
- **`src/main/java/org/acme/api/SolverTriggerResource.java`**: Job 실행을 트리거하는 REST API 컨트롤러.
- **`src/main/java/org/acme/solver/SolverRunner.java`**: 핵심 솔버 로직이 구현될 클래스.
- **`src/main/resources/application.properties`**:
    - `app.mode`: 애플리케이션 모드 설정 (기본값: API).
    - `quarkus.container-image.image`: Docker 이미지 경로 설정.

## 5. 빌드 및 실행 가이드

### 개발 모드 (Live Coding)
로컬에서 개발 시 아래 명령어로 실행합니다.
```bash
./mvnw quarkus:dev
```
- Dev UI: [http://localhost:8080/q/dev/](http://localhost:8080/q/dev/)

### 패키징 및 배포
`deploy.sh` 스크립트를 사용하여 빌드부터 배포까지 한 번에 수행할 수 있습니다.
```bash
./deploy.sh
```
이 스크립트는 다음 작업을 수행합니다:
1. `./mvnw clean package -DskipTests` 로 빌드.
2. Jib를 통해 Google Artifact Registry로 이미지 푸시.
3. `gcloud` 명령어로 Job과 Service를 업데이트.

### 수동 빌드
```bash
./mvnw package
```
- 결과물은 `target/quarkus-app/` 디렉토리에 생성됩니다.

## 6. 개발 규칙 (Conventions)
- **언어:** 소스 코드 주석 및 문서는 **영어**를 권장합니다.
- **구조:** 기능별로 패키지를 분리합니다 (예: `api`, `solver`).
- **로그:** `org.slf4j.Logger`를 사용하여 주요 이벤트(Job 시작/종료, 에러)를 로깅합니다.
