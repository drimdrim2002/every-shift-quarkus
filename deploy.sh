#!/bin/bash

# 동적 이미지 태그 생성 (타임스탬프)
IMAGE_TAG="v$(date +%Y%m%d-%H%M%S)"
IMAGE_NAME="asia-northeast3-docker.pkg.dev/every-shift-api/containers/hello-world-job:$IMAGE_TAG"

echo "🚀 [1/2] 빌드 및 이미지 푸시 시작 (Tag: $IMAGE_TAG)..."
# -Dquarkus.container-image.image 설정을 통해 동적 태그 적용
./mvnw clean package -Dmaven.test.skip=true -Dquarkus.container-image.image=$IMAGE_NAME

echo "🌐 [2/2] Cloud Run Service (API) 배포 중..."
gcloud run deploy every-shift-api-service \
  --image $IMAGE_NAME \
  --region asia-northeast3 \
  --set-env-vars APP_SOLVER_RUN_LOCALLY=false \
  --allow-unauthenticated \
  --min-instances 1 --memory 512Mi --cpu 1

echo "✅ 모든 배포가 완료되었습니다! (Image: $IMAGE_NAME)"
