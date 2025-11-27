# Backend

## 배포 전 준비 사항

- **필수 시크릿 생성**

  - Helm 차트에는 민감한 정보를 포함하지 않습니다.
  - 클러스터에 배포하기 전에 아래 명령으로 시크릿을 직접 생성하세요.
    ```bash
    kubectl create secret generic stockit-secrets \
      --from-literal=JWT_SECRET=<랜덤-값> \
      --from-literal=KIS_API_APPKEY=<한국투자증권-앱키> \
      --from-literal=KIS_API_APPSECRET=<한국투자증권-앱시크릿> \
      --from-literal=KAKAO_CLIENT_SECRET=<카카오-클라이언트-시크릿>
    ```
  - 각 값 설명:
    - `JWT_SECRET`: 충분히 긴 난수/문자열 (JWT 토큰 서명용)
    - `KIS_API_APPKEY`: 한국투자증권 Open API 앱키
    - `KIS_API_APPSECRET`: 한국투자증권 Open API 앱시크릿
    - `KAKAO_CLIENT_SECRET`: 카카오 OAuth 클라이언트 시크릿
  - 절대 버전 관리 저장소에 커밋하지 마세요.
  - 시크릿 이름 `stockit-secrets`는 애플리케이션 배포 시 그대로 사용됩니다.

- **기타 시크릿**
  - 데이터베이스, 외부 API 키 등 민감한 값도 동일한 방식으로 Kubernetes Secret에 수동으로 등록하고,
    `values.yaml` 또는 `application-*.yml`에는 참조만 남기도록 유지하세요.
