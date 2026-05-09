# Every Shift API Documentation for Frontend

현재 프로젝트는 직원 스케줄링을 위한 백엔드 AI 기능을 비동기 방식으로 제공합니다. 프론트엔드 개발 시 아래 가이드를 참고하여 연동해 주세요.

## 1. API 기본 정보

### Base URL
```
https://every-shift-api-service-554455861916.asia-northeast3.run.app
```

프론트엔드에서는 URL을 하드코딩하지 말고 `VITE_API_BASE_URL` 환경변수로 주입하는 것을 권장합니다.

```bash
# .env.development
VITE_API_BASE_URL=https://every-shift-api-service-554455861916.asia-northeast3.run.app
```

### 인증
현재 별도의 인증(Authorization Header)은 구현되지 않았습니다.

---

## 2. 개요 (Flow)

1. **Trigger**: 사용자가 스케줄 생성을 요청하면 서버는 즉시 작업 ID(`execution_id`)를 반환합니다.
2. **Polling**: 프론트엔드는 작업 ID를 사용해 주기적으로 상태를 조회합니다.
3. **Finish**: 상태가 `COMPLETED`가 되면 결과 데이터를 화면에 렌더링합니다.

```
┌─────────┐     POST /api/solve      ┌──────────┐     Cloud Tasks     ┌─────────┐
│ Front   │ ──────────────────────> │ API      │ ──────────────────> │ Worker  │
│  End    │ <────────────────────── │ Service  │                     │  Job    │
└─────────┘      (execution_id)     └──────────┘                     └─────────┘
     │                                   │                                 │
     │                                   │ <───────────────────────────────│
     │                                   │  saveResult() → Firestore        │
     │                                   │  (COMPLETED 상태 + 결과 저장)     │
     │                                   │                                 │
     │ GET /api/status/{id}              │                                 │
     └──────────────────────────────────>│                                 │
     <───────────────────────────────────│                                 │
```

---

## 3. API 상세

### [POST] 스케줄링 작업 실행

**Endpoint**
```
POST /api/solve
```

**Request Headers**
```
Content-Type: application/json
```

**Request Body**
```json
{
  "organization": {
    "id": "00000000-0000-0000-0000-000000000001",
    "name": "세브란스병원",
    "type": "hospital",
    "shifts": [
      {
        "id": "a5bcb7c0-b9b1-408d-9add-fd08c13b951c",
        "code": "D",
        "name": "Day",
        "start_time": "08:00:00",
        "end_time": "16:00:00"
      },
      {
        "id": "9ba021e7-1c4a-4f38-a577-ffc6dbcda56d",
        "code": "E",
        "name": "Evening",
        "start_time": "16:00:00",
        "end_time": "00:00:00"
      },
      {
        "id": "493edb73-a7a0-4751-8bc1-92745c8bf729",
        "code": "N",
        "name": "Night",
        "start_time": "00:00:00",
        "end_time": "08:00:00"
      }
    ],
    "lastHistoricalDate": "2025-11-26",
    "firstDraftDate": "2025-12-01",
    "publishLength": 4,
    "draftLength": 31
  },
  "employees": [
    {
      "employee_id": "3515886c-6359-4919-9c02-682565bb93c7",
      "name": "고소영",
      "available_shifts": ["D", "E", "N"],
      "skill_set": ["ALL"]
    }
  ],
  "history": [
    {
      "employee_id": "abf84b88-a0c8-4605-aa44-3aa9e5bb87a9",
      "shift_id": "493edb73-a7a0-4751-8bc1-92745c8bf729",
      "date": "2025-11-29",
      "is_locked": true
    }
  ],
  "undesirable": [
    {
      "employee_id": "abf84b88-a0c8-4605-aa44-3aa9e5bb87a9",
      "date": "2025-12-31",
      "is_locked": false
    }
  ],
  "requirements": [
    {
      "shiftId": "a5bcb7c0-b9b1-408d-9add-fd08c13b951c",
      "dayIndex": 0,
      "employeeCount": 3
    }
  ]
}
```

**필드 설명**

| 필드 | 타입 | 설명 |
|------|------|------|
| `organization` | Object | 병원/기관 정보 |
| `organization.shifts` | Array | 근무조(Shift) 정의 |
| `employees` | Array | 직원 목록 |
| `employees[].available_shifts` | Array[String] | 근무 가능한 시프트 코드 목록 |
| `employees[].skill_set` | Array[String] | 보유 기술 (`"ALL"`은 전체 가능) |
| `history` | Array | 과거 근무 기록 |
| `history[].is_locked` | Boolean | `true`면 확정된 기록 (변경 불가) |
| `undesirable` | Array | 근무 기피/희망 일자 |
| `requirements` | Array | 일자별 필요 인원 수 |

`undesirable` 입력은 내부 도메인에서 `AvailabilityType.UNDESIRED`로 매핑됩니다. 점수 계산 시에는 **Draft 시프트만 대상**이며, 지정 날짜와 시프트의 **실제시간 겹침 또는 논리일 일치** 조건을 만족하면 `undesired_soft_score`에 패널티가 반영됩니다. 단, **하나의 시프트는 최대 1회만 패널티**됩니다.

Night 논리일 규칙(컷오프 06:00):
- Night(`N`) 시작 시각이 `00:00~05:59`면 논리일은 `시작일 - 1일`
- Night(`N`) 시작 시각이 `06:00~23:59`면 논리일은 `시작일`
- Day/Evening은 논리일이 `시작일`

예시:
- `N 00:00~08:00` -> 논리일 `-1일`
- `N 23:00~07:00` -> 논리일 `당일`

추가 규칙:
- `undesirable` 내 동일 `employee_id + date` 중복 항목은 서버에서 자동 dedupe 됩니다.
- `undesirable.shift_id`, `undesirable.is_locked`는 현재 버전에서 제약 계산에 사용되지 않습니다(입력 호환용 필드).

**Response** (성공 - 200 OK)
```json
{
  "execution_id": "b6a0dc12-...",
  "status": "PENDING",
  "message": "Solver execution queued"
}
```

**Response** (검증 실패 - 400 Bad Request)
```json
{
  "error": "validation error message"
}
```

---

### [GET] 작업 상태 조회

**Endpoint**
```
GET /api/status/{id}
```

**URL Parameters**
| 파라미터 | 타입 | 설명 |
|----------|------|------|
| `id` | String | `execution_id` 값 |

**Response** (성공 - 200 OK)
```json
{
  "execution_id": "b6a0dc12-...",
  "tenant_id": "tenant-001",
  "organization_name": "세브란스병원",
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
  "result_json": null,
  "error_message": null,
  "created_at": "2025-12-01T10:00:00",
  "started_at": "2025-12-01T10:00:05",
  "completed_at": "2025-12-01T10:01:30"
}
```

**상태(status) 값**

| 값 | 설명 |
|----|------|
| `PENDING` | 대기 중 (아직 시작되지 않음) |
| `RUNNING` | Solver 실행 중 |
| `COMPLETED` | 완료 (result 확인) |
| `FAILED` | 실패 (error_message 확인) |

**Score 설명**
- `hard_score`: **0 이상**이어야 모든 필수 제약 조건을 만족 (음수면 제약 위반 있음)
- `night48_rest_soft_score`: Soft 1순위, 2연속 Night 후 다음 근무까지 48시간 휴식 부족분
- `night32_rest_soft_score`: Soft 2순위, Night 후 다음 Day shift까지 32시간 휴식 부족분
- `undesired_soft_score`: Soft 3순위, Off/비선호 요청일 배정 페널티
- `fair_soft_score`: Soft 4순위, 근무 유형별 분배 균형
- `desired_soft_score`: Soft 5순위, 선호일 배정 보상
- `legacy_soft_score_total`: 구버전 문서 호환용 단일 soft 점수

---

## 4. 프론트엔드 구현 가이드

### JavaScript/TypeScript 예시

```typescript
const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL ??
  "https://every-shift-api-service-554455861916.asia-northeast3.run.app";

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

// 1. 스케줄 생성 요청
async function createSchedule(requestData: any): Promise<string> {
  const response = await fetch(`${API_BASE_URL}/api/solve`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(requestData)
  });

  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.error || "Failed to create schedule");
  }

  const data = await response.json();
  return data.execution_id;
}

// 2. 상태 폴링
async function pollScheduleStatus(executionId: string): Promise<any> {
  const interval = 3000; // 3초 간격
  const maxAttempts = 60; // 최대 3분 대기

  for (let i = 0; i < maxAttempts; i++) {
    const response = await fetch(`${API_BASE_URL}/api/status/${executionId}`);
    const data = await response.json();
    const parsedScore = parseStatusScore(data.score);

    switch (data.status) {
      case "PENDING":
      case "RUNNING":
        await new Promise(resolve => setTimeout(resolve, interval));
        break;
      case "COMPLETED":
        return { ...data, parsed_score: parsedScore };
      case "FAILED":
        throw new Error(data.error_message || "Schedule generation failed");
    }
  }

  throw new Error("Timeout: Schedule generation took too long");
}

// 3. 전체 사용 예시
async function generateSchedule(requestData: any) {
  try {
    // 생성 요청
    const executionId = await createSchedule(requestData);
    console.log("Execution ID:", executionId);

    // 상태 폴링 시작
    const result = await pollScheduleStatus(executionId);
    console.log("Parsed Score:", result.parsed_score);

    // 결과 렌더링
    return result;

  } catch (error) {
    console.error("Error:", error);
    throw error;
  }
}
```

### UI 상태 관리

```typescript
type ScheduleState = "IDLE" | "PENDING" | "RUNNING" | "COMPLETED" | "FAILED";

function useScheduleGenerator() {
  const [state, setState] = useState<ScheduleState>("IDLE");
  const [executionId, setExecutionId] = useState<string | null>(null);
  const [result, setResult] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);

  const generate = async (requestData: any) => {
    setState("PENDING");
    setError(null);

    try {
      const id = await createSchedule(requestData);
      setExecutionId(id);

      // 폴링 시작
      pollAndUpdateStatus(id);
    } catch (e: any) {
      setError(e.message);
      setState("FAILED");
    }
  };

  const pollAndUpdateStatus = async (id: string) => {
    // ... 폴링 구현
  };

  return { state, result, error, generate };
}
```

---

## 5. 테스트용 샘플 데이터

프로젝트 내에 포함된 샘플 데이터 파일:
```
src/test/resources/json/sample.json
```

위 파일을 사용하여 API 테스트가 가능합니다.

```bash
# curl 테스트 예시
API_URL="https://every-shift-api-service-554455861916.asia-northeast3.run.app"

curl -X POST "$API_URL/api/solve" \
  -H "Content-Type: application/json" \
  -d @src/test/resources/json/sample.json
```

---

## 6. 주의사항

1. **폴링 간격**: 너무 짧은 간격(1초 미만)은 서버 부하를 유발할 수 있으므로 권장하지 않습니다. (권장: 3~5초)
2. **타임아웃**: Solver 실행 시 약 60초 정도 소요될 수 있습니다.
3. **에러 처리**: `FAILED` 상태 시 `error_message`를 확인하여 사용자에게 적절한 안내를 제공하세요.
4. **결과 데이터**: 현재 `result` 필드는 Firestore에 JSON 형태로 저장되며, 필요시 별도 파싱 로직이 필요할 수 있습니다.
