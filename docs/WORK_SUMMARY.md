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
  - `hardScore`, `undesiredSoftScore`, `fairSoftScore`, `desiredSoftScore`: OptaPlanner 점수
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
    "night48_rest_soft_score": -7,
    "night32_rest_soft_score": -30,
    "undesired_soft_score": -120,
    "fair_soft_score": -5400,
    "desired_soft_score": 240,
    "legacy_soft_score_total": null
  },
  "result": null,
  "error_message": null,
  "created_at": "2025-01-31T10:00:00",
  "started_at": "2025-01-31T10:00:01",
  "completed_at": "2025-01-31T10:01:00"
}
```

**Score 설명**
- `hard_score`: **0 이상**이어야 모든 필수 제약 조건을 만족 (음수면 제약 위반 있음)
- `night48_rest_soft_score`: Soft 1순위, 2연속 Night 후 다음 근무까지 48시간 휴식 부족분
- `night32_rest_soft_score`: Soft 2순위, Night 후 다음 Day shift까지 32시간 휴식 부족분
- `undesired_soft_score`: Soft 3순위, Off/비선호 요청일 배정 페널티
- `fair_soft_score`: Soft 4순위, 근무 유형별 분배 균형
- `desired_soft_score`: Soft 5순위, 선호일 배정 보상
- `legacy_soft_score_total`: 구버전 문서 호환용 단일 soft 점수

### 5.3 `/api/status` 점수 파서 계약 (프론트)

- 입력 우선순위:
  - 신규 스키마 우선: `hard_score`, `night48_rest_soft_score`, `night32_rest_soft_score`, `undesired_soft_score`, `fair_soft_score`, `desired_soft_score`, `legacy_soft_score_total`
  - 레거시 fallback: `hard_score`, `soft_score`
- 출력 계약:
  - `hard`
  - `night48Rest`
  - `night32Rest`
  - `undesired`
  - `fair`
  - `desired`
  - `legacyTotal`
  - `isLegacy`

```typescript
type StatusScoreV2 = {
  hard_score: number | null;
  night48_rest_soft_score: number | null;
  night32_rest_soft_score: number | null;
  undesired_soft_score: number | null;
  fair_soft_score: number | null;
  desired_soft_score: number | null;
  legacy_soft_score_total: number | null;
};

type StatusScoreLegacy = {
  hard_score: number;
  soft_score: number;
};

type ParsedStatusScore = {
  hard: number | null;
  night48Rest: number | null;
  night32Rest: number | null;
  undesired: number | null;
  fair: number | null;
  desired: number | null;
  legacyTotal: number | null;
  isLegacy: boolean;
};

function parseStatusScore(score: StatusScoreV2 | StatusScoreLegacy | null | undefined): ParsedStatusScore {
  if (
    score &&
    ("night48_rest_soft_score" in score ||
      "night32_rest_soft_score" in score ||
      "undesired_soft_score" in score ||
      "fair_soft_score" in score ||
      "desired_soft_score" in score ||
      "legacy_soft_score_total" in score)
  ) {
    return {
      hard: score.hard_score ?? null,
      night48Rest: score.night48_rest_soft_score ?? null,
      night32Rest: score.night32_rest_soft_score ?? null,
      undesired: score.undesired_soft_score ?? null,
      fair: score.fair_soft_score ?? null,
      desired: score.desired_soft_score ?? null,
      legacyTotal: score.legacy_soft_score_total ?? null,
      isLegacy: false
    };
  }

  if (score && "soft_score" in score) {
    return {
      hard: score.hard_score ?? null,
      night48Rest: null,
      night32Rest: null,
      undesired: null,
      fair: null,
      desired: null,
      legacyTotal: score.soft_score ?? null,
      isLegacy: true
    };
  }

  return {
    hard: null,
    night48Rest: null,
    night32Rest: null,
    undesired: null,
    fair: null,
    desired: null,
    legacyTotal: null,
    isLegacy: false
  };
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

# 프런트엔드에서는 동일 값을 VITE_API_BASE_URL에 주입해 사용
# 예: VITE_API_BASE_URL=https://every-shift-api-service-554455861916.asia-northeast3.run.app

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
