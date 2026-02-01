# CORS 정책 (CORS Policy)

## 개요

이 문서는 Every Shift API의 CORS(Cross-Origin Resource Sharing) 정책을 정의합니다.
CORS는 서로 다른 도메인이나 포트에서 실행되는 프론트엔드 애플리케이션(SPA)이 백엔드 API와 통신할 수 있도록 허용하기 위해 필요합니다.

## 정책 상세 내용

### 1. 허용된 출처 (Allowed Origins)

API는 다음 출처로부터의 요청을 명시적으로 수락합니다:

- **로컬 개발 환경:**
  - `http://localhost:3000` (React/Next.js 기본값)
  - `http://localhost:5173` (Vite 기본값)
  - `http://localhost:8080` (백엔드 자체)
- **운영 / 스테이징 환경:**
  - `https://*.run.app` (Google Cloud Run 도메인)
  - _(필요 시 실제 서비스 도메인을 여기에 추가)_

> **참고:** 보안 상의 이유로 운영 환경에서 와일드카드(`*`) 사용은 지양하며, 명시적인 도메인 목록을 관리합니다.

### 2. 허용된 메서드 (Allowed Methods)

다음 HTTP 메서드가 허용됩니다:

- `GET`: 리소스 조회 (예: 작업 상태 확인)
- `POST`: 작업 실행 (예: `api/solve` 호출)
- `OPTIONS`: CORS preflight 체크용
- `PUT`, `DELETE`: 리소스 수정 및 삭제 (필요 시)

### 3. 허용된 헤더 (Allowed Headers)

클라이언트는 다음 헤더를 포함하여 요청을 보낼 수 있습니다:

- `Accept`: 응답 형식 지정 (예: `application/json`)
- `Content-Type`: 요청 본문 형식 (예: `application/json`)
- `Authorization`: 인증용 Bearer 토큰
- `X-Execution-Id`: 작업 추적용 커스텀 헤더
- `X-Requested-With`: AJAX 요청 식별용

### 4. 노출된 헤더 (Exposed Headers)

브라우저 측 자바스크립트에서 접근할 수 있도록 노출된 응답 헤더입니다:

- `X-Execution-Id`: 프론트엔드에서 응답 헤더를 통해 실행 ID를 읽어야 할 경우 사용합니다.

## 구현 가이드 (Quarkus 설정)

이 정책은 `application.properties` 파일의 `quarkus.http.cors.*` 설정을 통해 제어됩니다.

```properties
quarkus.http.cors=true
# 허용 도메인
quarkus.http.cors.origins=http://localhost:8080,http://localhost:3000,http://localhost:5173,https://*.run.app
# 허용 메서드
quarkus.http.cors.methods=GET,POST,OPTIONS,PUT,DELETE
# 허용 헤더
quarkus.http.cors.headers=accept,authorization,content-type,x-requested-with,x-execution-id
# 노출 헤더
quarkus.http.cors.exposed-headers=x-execution-id
# 캐시 시간
quarkus.http.cors.access-control-max-age=PT24H
```
