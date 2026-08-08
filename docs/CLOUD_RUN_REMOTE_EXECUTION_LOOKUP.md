# Cloud Run 원격 실행 조회 가이드

이 문서는 GCP Cloud Run에서 실행된 솔버 Job의 **최근 실행 이력 확인 결과**와,
이후에도 동일하게 **input / 로그를 찾는 방법**을 정리한다.

- 조사일: 2026-08-08
- 대상 프로젝트: `every-shift-api`
- 리전: `asia-northeast3`
- 계정 컨텍스트: `gcloud` active project = `every-shift-api`

---

## 1. 인프라 식별자

| 구분 | 이름 / 값 |
|------|-----------|
| GCP Project | `every-shift-api` |
| Region | `asia-northeast3` |
| Cloud Run Job | `every-shift-job` |
| Cloud Run Service (API) | `every-shift-api-service` |
| API URL | `https://every-shift-api-service-x3l5zfq7ya-du.a.run.app` |
| Firestore collection (설정값) | `job-executions` |
| 배포 스크립트 | `deploy.sh` |

요청 흐름:

1. API (`APP_MODE=API`)가 요청을 받아 `JobExecution` 생성
2. `CloudRunJobInvoker`가 Job 실행 (`--execution-id`, `--input-data` Base64)
3. Job (`APP_MODE=JOB`)이 솔버 실행 후 결과 저장 시도

---

## 2. 조사 시점 최신 원격 실행 (2026-08-08)

### 2.1 요약

| 항목 | 값 |
|------|-----|
| 시각 (UTC) | 2026-08-08 **04:18:11 ~ 04:19:26** (약 1분 10초) |
| Cloud Run Execution | `every-shift-job-wz7cb` |
| 앱 executionId | `3e56517c-2682-4ee2-a89f-310a3813b983` |
| 상태 | 성공 (`succeededCount=1`, container `exit(0)`) |
| Score | `[0]hard/[-480/0/-6301/0]soft` |
| 조직 | 세브란스병원 (`type: hospital22`) |
| Draft | `firstDraftDate=2026-03-01`, `draftLength=31` |
| History 기준일 | `lastHistoricalDate=2026-02-28` |
| 직원 수 | 19명 (preceptor 연결 4건) |
| requirements | 93 |
| history / undesirable | 0 / 0 |
| publicHolidays | 14 |
| yearlyEmployeeStats | 19 |
| input JSON 크기 | 약 14,789 ~ 14,943 bytes |

### 2.2 API 트리거 로그 (Service)

`every-shift-api-service` 로그 (UTC):

```text
2026-08-08 04:18:11  JobExecution created: id=3e56517c-2682-4ee2-a89f-310a3813b983, organization=세브란스병원
2026-08-08 04:18:11  JobExecution created with id: 3e56517c-2682-4ee2-a89f-310a3813b983
2026-08-08 04:18:12  Cloud Run Job dispatched: executionId=3e56517c-2682-4ee2-a89f-310a3813b983,
                     runExecutionName=projects/every-shift-api/locations/asia-northeast3/operations/631489bb-0494-4f51-ad63-2b2e774b106b
```

### 2.3 Job 솔버 로그 (핵심)

`every-shift-job` / execution `every-shift-job-wz7cb`:

```text
>>> Application startup mode: JOB
>>> [JOB] Solver started
>>> Data received successfully (length: 14789)
>>> Status updated to RUNNING: 3e56517c-2682-4ee2-a89f-310a3813b983
--- Solver calculation started ---
Organization: 세브란스병원
Score: [0]hard/[-480/0/-6301/0]soft
Soft diagnostics: undesired_match_count=0, undesired_penalty_minutes=0
=== Solution Validation Completed - No hard-constraint violations found ===
--- Solver calculation ended ---
JobExecution result saved: id=3e56517c-2682-4ee2-a89f-310a3813b983, score=[0]hard/[-480/0/-6301/0]soft
>>> Result saved: 3e56517c-2682-4ee2-a89f-310a3813b983
Container called exit(0).
```

### 2.4 Input 특성

Job 컨테이너 args:

```text
--execution-id 3e56517c-2682-4ee2-a89f-310a3813b983
--input-data <Base64(PlanningRequest JSON)>
```

디코딩 결과 요약:

- 조직: 세브란스병원
- 교대: D / E / N
- 직원 예: 고소영, 권은비, 김고은, …, 최진실Q (총 19)
- history·undesirable 비어 있음
- preceptor_id가 설정된 직원이 4명
- draft 기간: 2026-03-01부터 31일

조사 당시 로컬 임시 복원 경로 (세션 한정, 영구 보관 아님):

- `/tmp/every-shift-remote-latest/input.json`
- `/tmp/every-shift-remote-latest/input-summary.json`

### 2.5 Firestore 조회 메모

앱 로그에는 `JobExecution result saved`가 기록되었다.
그러나 조사 시점에 REST로 `(default)` Firestore DB를 조회하면 404가 반환되었다.

```text
The database (default) does not exist for project every-shift-api
```

따라서 **input 원본 복원은 Cloud Run Job execution args / Cloud Logging이 더 확실**하다.
Firestore 저장 경로·DB 이름은 별도 점검이 필요하다.

### 2.6 같은 시점 실행 목록 (최근 일부)

| Execution | 완료(UTC) | 결과 |
|-----------|-----------|------|
| `every-shift-job-wz7cb` | 2026-08-08T04:19:26Z | 성공 (~1m10s) |
| `every-shift-job-gd4tn` | 2026-08-05T15:26:51Z | 성공 (~1m15s) |
| `every-shift-job-xrrlp` | 2026-08-05T13:44:14Z | 성공 (~1m15s) |
| `every-shift-job-k4v8l` | 2026-08-05T13:42:25Z | 성공 (~1m16s) |
| `every-shift-job-tvbpg` | 2026-08-03T09:32:27Z | 성공 (~1m12s) |

---

## 3. 다시 찾는 방법 (Runbook)

### 3.1 사전 조건

```bash
gcloud auth list
gcloud config set project every-shift-api
```

권한: Cloud Run Viewer + Logs Viewer (최소). Job args / 로그 조회에 사용.

### 3.2 최근 Job 실행 목록

```bash
gcloud run jobs executions list \
  --job=every-shift-job \
  --region=asia-northeast3 \
  --project=every-shift-api \
  --limit=10
```

### 3.3 Execution 상세 (input Base64 포함)

```bash
EXEC=every-shift-job-wz7cb   # 목록에서 선택

gcloud run jobs executions describe "$EXEC" \
  --region=asia-northeast3 \
  --project=every-shift-api
```

`spec.template.spec.containers[0].args`에서:

- `--execution-id`
- `--input-data` (PlanningRequest JSON의 Base64)

### 3.4 Job 로그 (시간 윈도우)

```bash
gcloud logging read \
  'resource.type="cloud_run_job"
   AND resource.labels.job_name="every-shift-job"
   AND timestamp>="2026-08-08T04:18:00Z"
   AND timestamp<="2026-08-08T04:21:00Z"' \
  --project=every-shift-api \
  --limit=200 \
  --order=asc \
  --format='value(timestamp,textPayload)'
```

### 3.5 Job 로그 (execution 이름 고정)

```bash
EXEC=every-shift-job-wz7cb

gcloud logging read \
  "resource.type=\"cloud_run_job\"
   AND resource.labels.job_name=\"every-shift-job\"
   AND labels.\"run.googleapis.com/execution_name\"=\"${EXEC}\"" \
  --project=every-shift-api \
  --limit=200 \
  --order=asc \
  --format='value(timestamp,textPayload)'
```

### 3.6 API 트리거 로그

```bash
gcloud logging read \
  'resource.type="cloud_run_revision"
   AND resource.labels.service_name="every-shift-api-service"
   AND (textPayload:("JobExecution") OR textPayload:("Cloud Run Job dispatched") OR textPayload:("Solver"))' \
  --project=every-shift-api \
  --limit=50 \
  --order=desc \
  --format='value(timestamp,textPayload)'
```

### 3.7 Input JSON 복원

`describe` 출력에서 `--input-data` 다음 Base64 문자열을 복사한 뒤:

```bash
# macOS
echo 'BASE64_STRING' | base64 -D > /tmp/remote-input.json

# 또는 Python
python3 - <<'PY'
import base64, json, sys
b64 = """BASE64_STRING"""
data = base64.b64decode(b64 + "=" * (-len(b64) % 4))
open("/tmp/remote-input.json", "wb").write(data)
req = json.loads(data)
org = req["organization"]
print("org:", org.get("name"))
print("firstDraftDate:", org.get("firstDraftDate"), "draftLength:", org.get("draftLength"))
print("employees:", len(req.get("employees") or []))
print("requirements:", len(req.get("requirements") or []))
PY
```

### 3.8 콘솔 UI

1. [Cloud Run Jobs](https://console.cloud.google.com/run/jobs?project=every-shift-api)
   → `every-shift-job` → **Executions** → 대상 execution → **Logs**
2. [Logs Explorer](https://console.cloud.google.com/logs/query?project=every-shift-api)
   → 위 섹션의 filter 쿼리 사용
3. [Cloud Run Services](https://console.cloud.google.com/run?project=every-shift-api)
   → `every-shift-api-service` → **Logs** (트리거 시점 확인)

---

## 4. 로컬 산출물과의 구분

| 구분 | 위치 | 비고 |
|------|------|------|
| 원격 Job 결과 | Cloud Logging / Job execution | 운영 요청 경로 |
| 로컬 테스트 export | `target/schedule-output/schedule-*.json` | `SolverRunnerTest` 등이 export |
| 테스트 입력 fixture | `src/test/resources/json/*.json` | request / preceptor / fairness 등 |

2026-08-08 로컬 `target/schedule-output` 파일은 **Maven 테스트 실행** 산출물이며,
위 원격 execution(`every-shift-job-wz7cb`)과는 별개다.

---

## 5. 관련 코드

| 역할 | 경로 |
|------|------|
| Job 엔트리 / args 파싱 | `src/main/java/org/acme/ApplicationMain.java` |
| 솔버 실행 | `src/main/java/org/acme/solver/SolverRunner.java` |
| API → Job 디스패치 | `src/main/java/org/acme/service/CloudRunJobInvoker.java` |
| 실행 상태/결과 저장 | `src/main/java/org/acme/service/JobExecutionService.java` |
| 배포 | `deploy.sh` |
| 기본 설정 | `src/main/resources/application.properties` |

---

## 6. 로컬 재현

이 실행 스냅샷은 로컬 테스트 픽스처로 저장되어 있다.

| 경로 | 설명 |
|------|------|
| `testdata/remote/3e56517c-2682-4ee2-a89f-310a3813b983/input.json` | PlanningRequest 원본 |
| `testdata/remote/3e56517c-2682-4ee2-a89f-310a3813b983/META.json` | 원격 메타 |
| `src/test/resources/json/remote/3e56517c-2682-4ee2-a89f-310a3813b983.json` | 테스트 클래스패스 복제본 |
| `scripts/run-remote-replay.sh` | 로컬 재현 스크립트 |
| `src/test/java/org/acme/solver/RemoteExecutionReplayTest.java` | 재현 테스트 |

```bash
# 권장
./scripts/run-remote-replay.sh

# 또는
./mvnw -Dtest=RemoteExecutionReplayTest test
```

결과 JSON: `target/schedule-output/schedule-*.json`  
JOB 모드 파일 입력: `ApplicationMain` 의 `--input-file` 지원.

---

## 7. 후속 점검 후보

1. Firestore DB 이름·모드 확인 (`job-executions` 실저장 위치)
2. soft score 첫 레벨 `-480` 원인 분석 (이번 실행 기준)
3. input / score / executionId를 로그에 구조화해 남기도록 관측성 보강
4. 이전 실행(`gd4tn`, `xrrlp` 등)과의 draft 기간·점수 비교

---

## 8. 변경 이력

| 날짜 | 내용 |
|------|------|
| 2026-08-08 | 최초 작성. 최신 원격 실행 `every-shift-job-wz7cb` / `3e56517c-...` 조사 결과 및 조회 runbook 문서화 |
| 2026-08-08 | 로컬 재현 픽스처·스크립트·테스트 경로 추가 |
