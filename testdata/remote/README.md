# Remote execution fixtures

Cloud Run Job 운영 실행 스냅샷을 로컬에서 재현하기 위한 입력 모음.

## 레이아웃

```text
testdata/remote/
  <executionId>/
    input.json      # PlanningRequest
    META.json       # 원격 메타 (점수, 시각, Cloud Run execution 이름)
    README.md       # 사용법
```

클래스패스 테스트용 복제본:

```text
src/test/resources/json/remote/<executionId>.json
```

## 현재 스냅샷

| executionId | Cloud Run execution | 날짜 (UTC) | 원격 score |
|-------------|---------------------|------------|------------|
| `3e56517c-2682-4ee2-a89f-310a3813b983` | `every-shift-job-wz7cb` | 2026-08-08 | `[0]hard/[-480/0/-6301/0]soft` |

## 빠른 실행

```bash
./scripts/run-remote-replay.sh
```
