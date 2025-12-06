#!/bin/bash

echo "🚀 [1/3] 빌드 및 이미지 푸시 시작..."
./mvnw clean package -DskipTests

echo "🤖 [2/3] Cloud Run Job (솔버) 배포 중..."
gcloud run jobs deploy hello-world-job \
  --image asia-northeast3-docker.pkg.dev/every-shift-api/containers/hello-world-job:v1 \
  --region asia-northeast3 \
  --set-env-vars APP_MODE=JOB \
  --tasks 1 --task-timeout 3600s --memory 4Gi --cpu 4

echo "🌐 [3/3] Cloud Run Service (API) 배포 중..."
gcloud run deploy every-shift-api-service \
  --image asia-northeast3-docker.pkg.dev/every-shift-api/containers/hello-world-job:v1 \
  --region asia-northeast3 \
  --set-env-vars APP_MODE=API \
  --allow-unauthenticated \
  --min-instances 0 --memory 512Mi --cpu 1

echo "✅ 모든 배포가 완료되었습니다!"