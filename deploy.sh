#!/bin/bash

# 동적 이미지 태그 생성 (타임스탬프)
IMAGE_TAG="v$(date +%Y%m%d-%H%M%S)"
IMAGE_NAME="asia-northeast3-docker.pkg.dev/every-shift-api/containers/hello-world-job:$IMAGE_TAG"

echo "🚀 [1/3] 빌드 및 이미지 푸시 시작 (Tag: $IMAGE_TAG)..."
# -Dquarkus.container-image.image 설정을 통해 동적 태그 적용
./mvnw clean package -Dmaven.test.skip=true -Dquarkus.container-image.image=$IMAGE_NAME

echo "🤖 [2/3] Cloud Run Job (솔버) 배포 중..."
gcloud run jobs deploy every-shift-job \
  --image $IMAGE_NAME \
  --region asia-northeast3 \
  --set-env-vars APP_MODE=JOB \
  --tasks 1 --task-timeout 3600s --memory 4Gi --cpu 4

echo "🌐 [3/3] Cloud Run Service (API) 배포 중..."
gcloud run deploy every-shift-api-service \
  --image $IMAGE_NAME \
  --region asia-northeast3 \
  --set-env-vars APP_MODE=API \
  --allow-unauthenticated \
  --min-instances 0 --memory 512Mi --cpu 1

echo "✅ 모든 배포가 완료되었습니다! (Image: $IMAGE_NAME)"