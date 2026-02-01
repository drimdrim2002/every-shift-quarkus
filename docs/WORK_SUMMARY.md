# Firestore 비동기 Solver API - 작업 요약

## 개요

이 프로젝트는 **Quarkus 기반의 직원 근무표 최적화 시스템**에 **비동기 Solver API**를 구현한 작업입니다. GCP Cloud Firestore를 사용하여 Job 실행 상태를 관리하고, 클라이언트는 Polling 방식으로 결과를 조회합니다.

---

## 1. 아키텍처 변화

### 기존 (동기 방식)
```
Client → API → Solver 실행 → 결과 반환
```

### 현재 (비동기 방식)
```
Client → POST /api/solve → executionId 반환 (즉시)
                    ↓
                Firestore에 문서 생성 (PENDING)
                    ↓
                Worker가 비동기 실행 (RUNNING → COMPLETED)
                    ↓
Client → GET /api/status/{execution_id} → 상태 조회 (Polling)
```

---

## 2. 신규 추가된 파일

### 2.1 API 레이어

#### `JobStatusResource.java`
- **경로**: `src/main/java/org/acme/api/JobStatusResource.java`
- **역할**: 상태 조회 API
- **엔드포인트**: `GET /api/status/{id}`
- **응답**: `StatusResponse` (상태, 점수, 결과, 에러 메시지 등)

#### `EveryShiftSolverTrigger.java` (수정됨)
- **경로**: `src/main/java/org/acme/api/EveryShiftSolverTrigger.java`
- **역할**: Solver 실행 요청 API
- **엔드포인트**: `POST /api/solve`
- **동작**:
  1. 요청 유효성 검사
  2. Firestore에 실행 문서 생성 (PENDING)
  3. 로컬 비동기 실행 또는 Cloud Tasks 전송
  4. executionId 즉시 반환

### 2.2 DTO (Data Transfer Objects)

| 파일 | 용도 |
|------|------|
| `SolveResponse.java` | POST /api/solve 응답 (execution_id, status, message) |
| `StatusResponse.java` | GET /api/status/{id} 응답 (상태, 점수, 결과 등) |
| `ErrorResponse.java` | 에러 응답 (error 메시지) |

### 2.3 Model

#### `ExecutionStatus.java`
- Job 실행 상태 Enum
- 값: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`

#### `JobExecution.java`
- Firestore에 저장되는 문서 모델
- 필드:
  - `id`: 문서 ID (UUID)
  - `tenantId`, `organizationName`: 조직 정보
  - `status`: 실행 상태
  - `resultJson`: 직렬화된 결과
  - `errorMessage`: 에러 메시지
  - `hardScore`, `softScore`: OptaPlanner 점수
  - `createdAt`, `startedAt`, `completedAt`: 타임스탬프
  - `requestJson`: 원본 요청 (디버깅용)

### 2.4 Service Layer

| 파일 | 역할 |
|------|------|
| `JobExecutionService.java` | Firestore CRUD operations |
| `FirestoreProducer.java` | Firestore 클라이언트 Bean 생성 |
| `CustomFirestore.java` | @Qualifier 애너테이션 |

### 2.5 Utility

#### `RequestValidator.java`
- `PlanningRequest` 유효성 검사
- 검사 항목:
  - Organization ID, Name
  - 최소 1개의 Shift 정의
  - 최소 1명의 Employee
  - Requirements 존재

---

## 3. 수정된 파일

### 3.1 `pom.xml`
```xml
<!-- Firestore 의존성 추가 -->
<dependency>
    <groupId>com.google.cloud</groupId>
    <artifactId>google-cloud-firestore</artifactId>
</dependency>

<!-- Quarkus Google Cloud Firestore 통합 -->
<dependency>
    <groupId>io.quarkiverse.googlecloudservices</groupId>
    <artifactId>quarkus-google-cloud-firestore</artifactId>
    <version>2.12.0</version>
</dependency>
```

### 3.2 `application.properties`
```properties
# 로컬 비동기 실행 모드
app.solver.run-locally=true

# Firestore 설정
gcp.firestore.collection=job-executions
```

### 3.3 `WorkerResource.java`
- `X-Execution-Id` 헤더 처리 추가
- Firestore 상태 업데이트 로직 추가 (RUNNING, COMPLETED, FAILED)

---

## 4. GCP 인프라 설정

### 4.1 Firestore 데이터베이스
```bash
gcloud firestore databases create \
    --database=default \
    --location=asia-northeast3 \
    --type=firestore-native
```

### 4.2 IAM 권한 부여
```bash
gcloud projects add-iam-policy-binding every-shift-api \
    --member="serviceAccount:554455861916-compute@developer.gserviceaccount.com" \
    --role="roles/datastore.user"
```

### 4.3 Cloud Run 리소스
| 항목 | 설정 |
|------|------|
| 메모리 | 1Gi |
| 타임아웃 | 3600초 |
| 리전 | asia-northeast3 |

---

## 5. API 명세

### 5.1 Solver 실행 요청

**Request**
```http
POST /api/solve
Content-Type: application/json

{
  "organization": {
    "id": "org-123",
    "name": "조직명",
    "shifts": [...]
  },
  "employees": [...],
  "history": [],
  "undesirable": [],
  "requirements": [...]
}
```

**Response (200 OK)**
```json
{
  "execution_id": "uuid-string",
  "status": "PENDING",
  "message": "Solver execution queued"
}
```

### 5.2 상태 조회

**Request**
```http
GET /api/status/{execution_id}
```

**Response (200 OK)**
```json
{
  "execution_id": "uuid-string",
  "tenant_id": "org-123",
  "organization_name": "조직명",
  "status": "COMPLETED",
  "score": {
    "hard_score": 0,
    "soft_score": 5
  },
  "result": null,
  "error_message": null,
  "created_at": "2025-01-31T10:00:00",
  "started_at": "2025-01-31T10:00:01",
  "completed_at": "2025-01-31T10:01:00"
}
```

---

## 6. 현재 진행 상황

### 완료된 작업 ✅
1. GCP Firestore 데이터베이스 생성
2. Cloud Run 서비스 계정에 Firestore 권한 부여
3. Cloud Run 메모리 증설 (256MiB → 1Gi)
4. 비동기 API 코드 구현 완료

### 진행 중인 작업 ⚠️
1. **Firestore 연결 문제 해결**
   - 에러: `NOT_FOUND: The database (default) does not exist for project every-shift-api`
   - 원인 분석 중: Google Cloud Firestore Java 클라이언트가 Datastore API를 호출

### 미완료 작업 ⬜
1. API 엔드포인트 테스트
2. 프론트엔드 연동 가이드 제공

---

## 7. Firestore 연결 문제 해결 방안

### 시도한 방법 (모두 실패)
1. `setDatabaseId("(default)")`
2. `setDatabaseId("")`
3. 환경 변수 `FIRESTORE_DATABASE_ID=(default)`
4. 환경 변수 `GOOGLE_CLOUD_FIRESTORE_DATABASE_ID=(default)`
5. `setDatabaseId()` 설정하지 않음

### 대안 해결 방안
1. **REST API 사용**: Firestore REST API 직접 호출
2. **gRPC 설정 확인**: Firestore gRPC 엔드포인트 명시적 설정
3. **대안 저장소**: Cloud SQL 또는 Memorystore (Redis) 사용
4. **클라이언트 버전 변경**: 다른 버전의 Firestore 클라이언트 테스트

---

## 8. 유용한 명령어

### 배포
```bash
./deploy.sh
```

### 로그 확인
```bash
# 전체 로그
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=every-shift-api-service"

# 최근 로그 (50개, 5분 이내)
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=every-shift-api-service" --limit=50 --freshness=5m

# 에러 로그만
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=every-shift-api-service AND severity>=ERROR"
```

### API 테스트
```bash
API_URL="https://every-shift-api-service-554455861916.asia-northeast3.run.app"

# Solver 실행 요청
curl -X POST "$API_URL/api/solve" \
  -H "Content-Type: application/json" \
  -d @request.json

# 상태 조회
curl -X GET "$API_URL/api/status/{execution_id}"
```

---

## 9. 다음 세션에서 해결할 문제

1. **Firestore 연결 문제 해결** (최우선)
2. API 엔드포인트 정상 동작 확인
3. 프론트엔드 연동 가이드 작성
