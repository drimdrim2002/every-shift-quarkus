#!/usr/bin/env bash
# 원격 Cloud Run 실행 스냅샷을 로컬에서 재현한다.
#
# 기본 대상:
#   executionId = 3e56517c-2682-4ee2-a89f-310a3813b983
#   Cloud Run   = every-shift-job-wz7cb (2026-08-08)
#
# 사용법:
#   ./scripts/run-remote-replay.sh              # Maven 테스트로 재현 (권장)
#   ./scripts/run-remote-replay.sh test
#   ./scripts/run-remote-replay.sh job          # APP_MODE=JOB + --input-file
#   ./scripts/run-remote-replay.sh job 30       # JOB 모드, spent-limit 30초
#
# 환경 변수:
#   EXECUTION_ID   기본: 3e56517c-2682-4ee2-a89f-310a3813b983
#   SPENT_LIMIT    초 단위 솔버 시간 (test 프로필 기본 60, job 모드 기본 60)
#   EXPORT_DIR     결과 JSON 출력 경로 (기본: target/schedule-output)

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

EXECUTION_ID="${EXECUTION_ID:-3e56517c-2682-4ee2-a89f-310a3813b983}"
MODE="${1:-test}"
SPENT_LIMIT="${2:-${SPENT_LIMIT:-60}}"
EXPORT_DIR="${EXPORT_DIR:-target/schedule-output}"

INPUT_FILE="testdata/remote/${EXECUTION_ID}/input.json"
TEST_RESOURCE="src/test/resources/json/remote/${EXECUTION_ID}.json"

if [[ ! -f "$INPUT_FILE" ]]; then
  echo "❌ input 파일이 없습니다: $INPUT_FILE" >&2
  echo "   사용 가능한 스냅샷:" >&2
  ls -1 testdata/remote 2>/dev/null || true
  exit 1
fi

if [[ ! -f "$TEST_RESOURCE" ]]; then
  echo "⚠️  테스트 리소스가 없어 input을 복사합니다: $TEST_RESOURCE"
  mkdir -p "$(dirname "$TEST_RESOURCE")"
  cp "$INPUT_FILE" "$TEST_RESOURCE"
fi

echo "🔎 Remote replay"
echo "  - executionId : ${EXECUTION_ID}"
echo "  - mode        : ${MODE}"
echo "  - spent-limit : ${SPENT_LIMIT}s"
echo "  - input       : ${INPUT_FILE}"
echo "  - export dir  : ${EXPORT_DIR}"
if [[ -f "testdata/remote/${EXECUTION_ID}/META.json" ]]; then
  echo "  - meta        : testdata/remote/${EXECUTION_ID}/META.json"
fi
echo

case "$MODE" in
  test|maven)
    # QuarkusTest 경로: Firestore 없이 SolverRunner만 검증 + schedule export
    ./mvnw -Dtest=RemoteExecutionReplayTest \
      -Dsolver.termination.spent-limit="${SPENT_LIMIT}" \
      -Dapp.export.output-dir="${EXPORT_DIR}" \
      test
    ;;
  job|dev)
    # Cloud Run Job과 동일 엔트리(APP_MODE=JOB). 로컬 파일 입력 지원(--input-file).
    # execution-id를 넘기지 않아 Firestore 상태 갱신을 시도하지 않는다.
    mkdir -p "${EXPORT_DIR}"
    export APP_MODE=JOB
    export EXPORT_DIR
    ./mvnw quarkus:dev \
      -Dquarkus.args="--input-file ${INPUT_FILE}" \
      -Dapp.mode=JOB \
      -Dsolver.termination.spent-limit="${SPENT_LIMIT}" \
      -Dapp.export.output-dir="${EXPORT_DIR}" \
      -Dapp.export.enabled=true
    ;;
  *)
    echo "❌ 알 수 없는 mode: $MODE (test|job)" >&2
    exit 1
    ;;
esac

echo
echo "✅ 완료. 결과 JSON은 ${EXPORT_DIR}/ 를 확인하세요."
if [[ -d "${EXPORT_DIR}" ]]; then
  ls -lt "${EXPORT_DIR}" | head -5 || true
fi
