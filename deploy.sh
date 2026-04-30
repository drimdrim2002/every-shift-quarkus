#!/bin/bash

set -euo pipefail

PROJECT_ID="${PROJECT_ID:-every-shift-api}"
REGION="${REGION:-asia-northeast3}"
ARTIFACT_REPOSITORY="${ARTIFACT_REPOSITORY:-containers}"
IMAGE_NAME_BASE="${IMAGE_NAME_BASE:-hello-world-job}"
JOB_NAME="${JOB_NAME:-every-shift-job}"
SERVICE_NAME="${SERVICE_NAME:-every-shift-api-service}"
FIRESTORE_COLLECTION="${FIRESTORE_COLLECTION:-job-executions}"
DRY_RUN="${DRY_RUN:-0}"

IMAGE_TAG="v$(date +%Y%m%d-%H%M%S)"
IMAGE_NAME="${REGION}-docker.pkg.dev/${PROJECT_ID}/${ARTIFACT_REPOSITORY}/${IMAGE_NAME_BASE}:${IMAGE_TAG}"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "❌ 필수 명령을 찾을 수 없습니다: $1" >&2
    exit 1
  fi
}

run_cmd() {
  if [[ "$DRY_RUN" == "1" ]]; then
    printf '[DRY RUN] '
    printf '%q ' "$@"
    printf '\n'
    return 0
  fi

  "$@"
}

print_context() {
  local active_account active_project

  active_account="$(gcloud auth list --filter=status:ACTIVE --format='value(account)' || true)"
  active_project="$(gcloud config get-value project || true)"

  echo "🔎 배포 컨텍스트 확인"
  echo "  - Active account : ${active_account:-<unknown>}"
  echo "  - Active project : ${active_project:-<unknown>}"
  echo "  - Target project : ${PROJECT_ID}"
  echo "  - Region         : ${REGION}"
  echo "  - Job            : ${JOB_NAME}"
  echo "  - Service        : ${SERVICE_NAME}"
  echo "  - Image          : ${IMAGE_NAME}"

  if [[ -z "${active_account}" ]]; then
    echo "❌ 활성 gcloud 계정을 확인할 수 없습니다." >&2
    exit 1
  fi

  if [[ "${active_project}" != "${PROJECT_ID}" ]]; then
    echo "❌ 현재 gcloud 프로젝트(${active_project:-<unknown>})가 배포 대상 프로젝트(${PROJECT_ID})와 다릅니다." >&2
    echo "   필요 시: gcloud config set project ${PROJECT_ID}" >&2
    exit 1
  fi
}

warn_dirty_tree() {
  if [[ -n "$(git status --short 2>/dev/null || true)" ]]; then
    echo "⚠️ 현재 작업 트리에 커밋되지 않은 변경사항이 있습니다."
    echo "   의도한 배포인지 확인하세요."
  fi
}

preflight_checks() {
  require_command git
  require_command gcloud
  require_command ./mvnw

  print_context
  warn_dirty_tree

  run_cmd gcloud projects describe "${PROJECT_ID}" >/dev/null
  run_cmd gcloud artifacts repositories describe "${ARTIFACT_REPOSITORY}" --location "${REGION}" >/dev/null

  if run_cmd gcloud run jobs describe "${JOB_NAME}" --region "${REGION}" >/dev/null 2>&1; then
    echo "ℹ️ 기존 Cloud Run Job(${JOB_NAME})을 찾았습니다."
  else
    echo "ℹ️ 기존 Cloud Run Job(${JOB_NAME})이 없어 새로 생성될 수 있습니다."
  fi

  if run_cmd gcloud run services describe "${SERVICE_NAME}" --region "${REGION}" >/dev/null 2>&1; then
    echo "ℹ️ 기존 Cloud Run Service(${SERVICE_NAME})를 찾았습니다."
  else
    echo "ℹ️ 기존 Cloud Run Service(${SERVICE_NAME})가 없어 새로 생성될 수 있습니다."
  fi
}

echo "🚦 사전 점검 시작..."
preflight_checks

echo "🚀 [1/3] 빌드 및 이미지 푸시 시작 (Tag: ${IMAGE_TAG})..."
run_cmd ./mvnw clean package -Dmaven.test.skip=true "-Dquarkus.container-image.image=${IMAGE_NAME}"

echo "🤖 [2/3] Cloud Run Job 배포 중..."
run_cmd gcloud run jobs deploy "${JOB_NAME}" \
  --image "${IMAGE_NAME}" \
  --region "${REGION}" \
  --set-env-vars "APP_MODE=JOB,APP_SOLVER_RUN_LOCALLY=false,GCP_FIRESTORE_COLLECTION=${FIRESTORE_COLLECTION}" \
  --tasks 1 --task-timeout 900s --memory 4Gi --cpu 4

echo "🌐 [3/3] Cloud Run Service 배포 중..."
run_cmd gcloud run deploy "${SERVICE_NAME}" \
  --image "${IMAGE_NAME}" \
  --region "${REGION}" \
  --set-env-vars "APP_MODE=API,APP_SOLVER_RUN_LOCALLY=false,APP_DISPATCH_MODE=CLOUD_RUN_JOB,GCP_RUN_JOB_NAME=${JOB_NAME},GCP_RUN_REGION=${REGION},GCP_FIRESTORE_COLLECTION=${FIRESTORE_COLLECTION}" \
  --no-allow-unauthenticated \
  --min-instances 1 --memory 512Mi --cpu 1 --timeout 60s

echo "🔐 권한 확인"
echo "  - API 서비스 계정에 Cloud Run Job 실행 권한(run.jobs.run / run.jobs.runWithOverrides)이 필요합니다."
echo "  - 예시: gcloud projects add-iam-policy-binding ${PROJECT_ID} --member=\"serviceAccount:<API_SERVICE_ACCOUNT>\" --role=\"roles/run.developer\""

echo "✅ 배포 스크립트가 완료되었습니다. (Image: ${IMAGE_NAME})"
