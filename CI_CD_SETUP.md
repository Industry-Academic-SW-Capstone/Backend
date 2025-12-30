# CI/CD 파이프라인 설정 가이드

## 완료된 작업

✅ Docker 이미지 빌드 및 푸시 (0.1.5)
✅ Helm 차트 values.yaml 이미지 태그 업데이트
✅ GitHub Actions CI/CD 워크플로우 파일 생성

## GitHub Secrets 설정

CI/CD 파이프라인이 작동하려면 다음 GitHub Secrets를 설정해야 합니다:

### 1. Docker Hub 인증 정보

- **DOCKER_USERNAME**: Docker Hub 사용자명
- **DOCKER_PASSWORD**: Docker Hub 비밀번호 또는 Access Token

### 2. GCP/GKE 인증 정보

- **GCP_PROJECT_ID**: GCP 프로젝트 ID
- **GCP_GKE_CLUSTER**: GKE 클러스터 이름
- **GCP_GKE_ZONE**: GKE 클러스터 존 (예: asia-northeast3-a)
- **GCP_SA_KEY**: GKE 접근 권한이 있는 서비스 계정 키 (JSON 형식)

### GitHub Secrets 설정 방법

1. GitHub 저장소로 이동
2. **Settings** → **Secrets and variables** → **Actions** 클릭
3. **New repository secret** 클릭
4. 위의 각 Secret을 추가

### GCP 서비스 계정 키 생성 방법

```bash
# 서비스 계정 생성
gcloud iam service-accounts create github-actions \
    --display-name="GitHub Actions Service Account"

# 필요한 권한 부여
gcloud projects add-iam-policy-binding YOUR_PROJECT_ID \
    --member="serviceAccount:github-actions@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
    --role="roles/container.developer"

# 키 생성 및 다운로드
gcloud iam service-accounts keys create key.json \
    --iam-account=github-actions@YOUR_PROJECT_ID.iam.gserviceaccount.com
```

생성된 `key.json` 파일의 내용을 `GCP_SA_KEY` Secret에 추가하세요.

## 워크플로우 동작

### 트리거 조건

- `develop`, `main`, `feat/**` 브랜치에 push
- Pull Request 생성
- 수동 실행 (workflow_dispatch)

### 작업 흐름

1. **Build and Test**

   - JDK 21 설정
   - Gradle 빌드
   - 테스트 실행

2. **Build and Push Docker Image** (PR 제외)

   - Docker 이미지 빌드
   - 이미지 태그 생성:
     - `main`: `YYYYMMDD-<commit-sha>`
     - `develop`: `develop-<commit-sha>`
     - 기타: `<branch-name>-<commit-sha>`
   - Docker Hub에 푸시

3. **Deploy to GKE** (develop/main 브랜치만)
   - GKE 클러스터 인증
   - Helm 차트 업데이트
   - 배포 실행
   - 배포 상태 확인

## 수동 배포 (현재)

최신 이미지(0.1.5)를 배포하려면:

```bash
helm upgrade --install stockit-release ./stockit-backend-chart \
  --namespace default \
  --set backend.image.tag="0.1.5" \
  --wait \
  --timeout 10m
```

## 다음 단계

1. GitHub Secrets 설정
2. `develop` 또는 `main` 브랜치에 push하여 자동 배포 테스트
3. 필요시 워크플로우 파일 수정
