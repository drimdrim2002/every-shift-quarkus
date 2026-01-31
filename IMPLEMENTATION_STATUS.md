# Firestore 비동기 Solver API - 구현 현황

## 개요

비동기 Solver API 구현을 위해 GCP Firestore 데이터베이스를 생성하고 배포를 진행 중입니다. 현재 Firestore 연결 문제를 해결하는 중입니다.

---

## 완료된 작업

### 1. ✅ GCP Firestore 데이터베이스 생성

```bash
gcloud firestore databases create \
    --database=default \
    --location=asia-northeast3 \
    --type=firestore-native
```

- 리전: `asia-northeast3`
- 타입: `FIRESTORE_NATIVE`
- 데이터베이스 이름: `default`

### 2. ✅ Cloud Run 서비스 계정에 Firestore 권한 부여

```bash
gcloud projects add-iam-policy-binding every-shift-api \
    --member="serviceAccount:554455861916-compute@developer.gserviceaccount.com" \
    --role="roles/datastore.user"
```

### 3. ✅ 메모리 설정 증설

Cloud Run 서비스 메모리를 `256MiB` → `1Gi`로 증설 (Firestore 클라이언트 초기화 메모리 부족 문제 해결)

### 4. ✅ 코드 변경 사항

#### 추가된 파일
- `src/main/java/org/acme/api/JobStatusResource.java` - 상태 조회 API
- `src/main/java/org/acme/api/dto/ErrorResponse.java` - 에러 응답 DTO
- `src/main/java/org/acme/api/dto/SolveResponse.java` - Solver 응답 DTO
- `src/main/java/org/acme/api/dto/StatusResponse.java` - 상태 응답 DTO
- `src/main/java/org/acme/model/ExecutionStatus.java` - 실행 상태 Enum
- `src/main/java/org/acme/model/JobExecution.java` - Job 실행 모델
- `src/main/java/org/acme/service/JobExecutionService.java` - Firestore 서비스
- `src/main/java/org/acme/service/FirestoreProducer.java` - Firestore 클라이언트 Producer
- `src/main/java/org/acme/service/CustomFirestore.java` - Firestore Qualifier
- `src/main/java/org/acme/util/RequestValidator.java` - 요청 유효성 검사

#### 수정된 파일
- `pom.xml` - Quarkus Google Cloud Firestore 의존성 추가 (`quarkus-google-cloud-firestore:2.12.0`)
- `src/main/java/org/acme/resource/WorkerResource.java` - 중복 엔드포인트 제거
- `src/main/resources/application.properties` - Firestore 설정 추가

---

## 진행 중인 작업

### 4. ⚠️ Firestore 연결 문제 해결 중

**에러 메시지:**
```
NOT_FOUND: The database (default) does not exist for project every-shift-api
Please visit https://console.cloud.google.com/datastore/setup?project=every-shift-api
```

**문제 분석:**
- Firestore 데이터베이스는 존재하고 `FIRESTORE_NATIVE` 타입임을 확인
- Google Cloud Firestore Java 클라이언트가 Datastore API를 호출하고 있음
- `FirestoreOptions.newBuilder().setDatabaseId("")` 설정으로도 문제 지속

**시도한 해결 방안:**
1. `setDatabaseId("(default)")` - 실패
2. `setDatabaseId("")` - 실패
3. 환경 변수 `FIRESTORE_DATABASE_ID=(default)` 설정 - 실패
4. 환경 변수 `GOOGLE_CLOUD_FIRESTORE_DATABASE_ID=(default)` 설정 - 실패
5. `setDatabaseId()` 설정하지 않음 - 실패 (Datastore API 호출)

**최신 FirestoreProducer 코드:**
```java
FirestoreOptions.Builder builder = FirestoreOptions.newBuilder()
        .setProjectId(projectId)
        .setDatabaseId(""); // 빈 문자열은 기본 Firestore 데이터베이스를 사용
```

### 5. ⬜ API 엔드포인트 테스트 - 진행 중

`/api/solve` 엔드포인트가 Firestore 연결 실패로 인해 500 에러 반환 중.

---

## 미완료 작업

### 6. ⬜ API 엔드포인트 테스트 완료

- [ ] `/api/solve` - Solver 실행 요청 (현재 500 에러)
- [ ] `/api/status/{execution_id}` - 상태 조회
- [ ] Firestore 문서 생성 및 상태 업데이트 확인

### 7. ⬜ 모니터링 및 로그 확인

```bash
# Cloud Run 로그 확인
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=every-shift-api-service"
```

### 8. ⬜ 프론트엔드 연동 가이드 제공

```javascript
// 비동기 Solver API 연동 가이드 (작업 예정)

// 1. Solver 실행 요청
async function submitSolver(request) {
    const response = await fetch('/api/solve', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request)
    });
    const data = await response.json();
    return data.execution_id;
}

// 2. 상태 폴링
async function pollSolverStatus(executionId) {
    // ... Exponential Backoff 구현
}
```

---

## 환경 설정

### Cloud Run 환경 변수

| 변수명 | 값 | 설명 |
|--------|-----|------|
| `PORT` | `8080` | HTTP 포트 |
| `APP_MODE` | `API` | API 모드 |
| `app.solver.run-locally` | `true` | 로컬 비동기 실행 |
| `GCP_FIRESTORE_DATABASE_ID` | `(default)` | Firestore DB ID (현재 미사용) |

### Cloud Run 리소스

| 항목 | 설정 |
|------|------|
| 메모리 | `1Gi` |
| 타임아웃 | `3600` 초 |
| 리전 | `asia-northeast3` |

---

## 다음 세션에서 해결할 문제

### Firestore 연결 문제 해결 방안

1. **REST API 사용 고려**
   - Firestore REST API를 직접 호출하는 방식으로 변경
   - Google Cloud Client Library for HTTP 사용

2. **gRPC 설정 확인**
   - Firestore gRPC 엔드포인트 명시적 설정
   - `firestore.googleapis.com` 호스트 지정

3. **대안 저장소 고려**
   - Cloud SQL 사용
   - Memorystore (Redis) 사용

4. **Firestore 클라이언트 버전 변경**
   - 현재: `google-cloud-firestore:3.28.0`
   - 다른 버전 테스트

---

## 명령어 모음

### 배포
```bash
./deploy.sh
```

### 로그 확인
```bash
# 전체 로그
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=every-shift-api-service"

# 최근 로그
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=every-shift-api-service" --limit=50 --freshness=5m

# 에러 로그만
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=every-shift-api-service AND severity>=ERROR"
```

### 테스트
```bash
API_URL="https://every-shift-api-service-554455861916.asia-northeast3.run.app"
curl -X POST "$API_URL/api/solve" \
  -H "Content-Type: application/json" \
  -d '{...}'
```

---

## 참고 링크

- [Cloud Firestore Admin API](https://cloud.google.com/firestore/docs/reference/rest)
- [Firestore Java Client Library](https://cloud.google.com/java/docs/reference/google-cloud-firestore/latest/overview)
- [Quarkus Google Cloud Services](https://quarkiverse.github.io/quarkiverse-docs/quarkus-google-cloud-services/dev/)
