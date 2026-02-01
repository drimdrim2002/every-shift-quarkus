# Every Shift API Documentation for Frontend

현재 프로젝트는 직원 스케줄링을 위한 백엔드 AI 기능을 비동기 방식으로 제공합니다. 프론트엔드 개발 시 아래 가이드를 참고하여 연동해 주세요.

## 1. 개요 (Flow)

1. **Trigger**: 사용자가 스케줄 생성을 요청하면 서버는 즉시 작업 ID(`executionId`)를 반환합니다.
2. **Polling**: 프론트엔드는 작업 ID를 사용해 주기적으로 상태를 조회합니다.
3. **Finish**: 상태가 `COMPLETED`가 되면 결과 데이터를 화면에 렌더링합니다.

---

## 2. API 상세

### [POST] 스케줄링 작업 실행

- **Endpoint**: `/api/solve`
- **Request Body**: `src/test/resources/json/sample.json` (제공된 샘플 파일) 구조를 따릅니다.
- **주요 필드**:
  - `organization`: 병원 정보 및 근무조(Shift) 정의
  - `employees`: 직원 목록 및 근무 가능 정보
  - `history`: 과거 근무 기록
  - `undesirable`: 근무 기피/희망 사항
  - `requirements`: 일자별 필요 인원
- **Response**:
  ```json
  {
    "executionId": "b6a0dc12-..."
  }
  ```

### [GET] 작업 상태 조회

- **Endpoint**: `/api/status/{id}`
- **Response 주요 필드**:
  - `status`: 작업 상태 (`PENDING`, `RUNNING`, `COMPLETED`, `FAILED`)
  - `score`:
    - `hard_score`: 0 이상이면 모든 필수 제약 조건 만족
    - `soft_score`: 점수가 높을수록(0에 가까울수록) 권장 사항을 잘 지킨 스케줄
  - `result`: 최종 생성된 스케줄 결과 데이터 (상태가 `COMPLETED`일 때만 유효)
  - `error_message`: 상태가 `FAILED`일 경우 상세 에러 내용
  - `created_at`, `started_at`, `completed_at`: 각 단계별 타임스탬프

---

## 3. 프론트엔드 구현 워크플로우

1. **생성 클릭**: 사용자가 스케줄링 데이터와 함께 생성 버튼을 누릅니다.
2. **API 호출**: `POST /api/solve`를 호출하여 작업을 위임합니다.
3. **응답 대기**: 서버로부터 즉시 받은 `executionId`를 저장합니다.
4. **상태 폴링**: `setInterval` 등을 이용해 3~5초 간격으로 `GET /api/status/{id}`를 호출합니다.
5. **UI 업데이트**:
   - `RUNNING`: 화면에 "AI가 스케줄을 최적화 중입니다..." 등의 메시지와 로딩 애니메이션 노출.
   - `COMPLETED`: 응답의 `result` 데이터를 파싱하여 스케줄표(Calendar 등)를 업데이트합니다.
   - `FAILED`: 사용자에게 생성 실패 알림을 띄우고 다시 시도하도록 안내합니다.
