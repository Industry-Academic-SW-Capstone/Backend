# 개발 가이드

로컬 개발 환경 설정, 빌드/실행, 테스트, 마이그레이션을 설명합니다.

## 요구 사항

- JDK 21 (Temurin 권장)
- Docker / Docker Compose (dev 프로파일 서비스용)
- `.env` 파일 (환경 변수 — 아래 참고)

## 빌드 & 실행

```bash
./gradlew build              # 전체 빌드 (테스트 포함)
./gradlew build -x test      # 테스트 제외 빌드
./gradlew clean build        # 클린 빌드
./gradlew bootRun            # 실행
```

Docker Compose:

```bash
docker-compose up -d          # Traefik, PostgreSQL, Redis, Backend, 모니터링
docker-compose down
docker-compose build          # 백엔드 이미지 재빌드
```

## Spring 프로파일

| 프로파일 | 용도 |
|----------|------|
| `local` | 로컬 DB/Redis (localhost) |
| `dev` | Docker 서비스 (**기본값**) |
| `prod` | 외부 DB (배포 서버) |

```bash
-Dspring.profiles.active=local          # 또는
SPRING_PROFILES_ACTIVE=local
```

`local` / `dev`는 `.env`를 `optional:file:.env[.properties]`로 로드합니다.

## 환경 변수 (`.env`)

로컬에서 필요한 주요 키 (버전 관리에 커밋 금지):

| 변수 | 설명 |
|------|------|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_URL` | PostgreSQL |
| `KIS_API_APPKEY` / `KIS_API_APPSECRET` | 한국투자증권 Open API |
| `KAKAO_REST_API_KEY` / `KAKAO_REDIRECT_URI` | Kakao OAuth |
| `JWT_SECRET` | JWT 서명 |
| `GEMINI_API_KEY` / `CLAUDE_API_KEY` | AI 분석 |
| `DART_API_KEY` | DART 공시 |
| `PYTHON_ANALYSIS_URL` | 외부 Python 분석 서비스 |

> `KAKAO_CLIENT_SECRET`, `FIREBASE_CREDENTIALS_BASE64` 등은 운영 배포 시
> GitHub Secrets로 주입됩니다. → [deployment.md](deployment.md)

## 테스트

```bash
./gradlew test                                # 전체
./gradlew test --tests "ClassName"            # 특정 클래스
./gradlew test --tests "ClassName.methodName" # 특정 메서드
```

- **단위/통합**: JUnit 5 + Testcontainers(PostgreSQL) — 외부 DB 불필요.
- **동시성**: `LimitOrderMatchingServiceConcurrencyTest` — 동시 주문 매칭 시 데이터 정합성 검증.
  → [matching-engine.md](matching-engine.md)
- **부하(k6)**:
  ```bash
  docker-compose --profile load-test run --rm k6 run /scripts/matching-engine-test.js
  ```
  스크립트: `k6/scripts/`

## 데이터베이스 마이그레이션

- Flyway SQL: `src/main/resources/db/migration/` (현재 `V2`, `V6`~`V9`).
- Flyway **자동 실행은 비활성화**되어 있으며 초기화는 `global/config/FlywayConfig.java`가 담당합니다.
- 새 마이그레이션 추가 시 다음 버전 번호로 `V{n}__{설명}.sql` 파일을 생성합니다.

## 코드 컨벤션

- **Lombok**: `@Getter`, `@Builder`, `@NoArgsConstructor(access = PROTECTED)`, `@Slf4j`
- **DTO**: `{Action}Request` / `{Domain}Response` (`dto/` 패키지)
- **REST 경로**: `/api/{domain}`
- **검증**: 요청 바디에 JSR-303 `@Valid`
- **API 문서**: SpringDoc OpenAPI 애노테이션(`@Operation`, `@Tag`) → Swagger UI `/swagger-ui/`
