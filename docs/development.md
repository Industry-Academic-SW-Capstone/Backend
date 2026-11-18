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
docker-compose up -d          # PostgreSQL(pgvector), Backend
docker-compose --profile ai up -d   # AI 서버까지 (AI_IMAGE 로 이미지 지정)
docker-compose down
docker-compose build          # 백엔드 이미지 재빌드
```

## Spring 프로파일

| 프로파일 | 용도 |
|----------|------|
| `local` | 로컬 DB (localhost) |
| `dev` | Docker 서비스 (**기본값**) |
| `prod` | EC2 배포 (`docker-compose.prod.yml`, 빈 DB 면 테이블·인덱스·기초 데이터 생성) |

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
| `PYTHON_ANALYSIS_URL` | Python AI 서버 (compose 에서는 `http://ai:8000`) |
| `NEWS_DB_PASSWORD` | 뉴스 RAG DB(`stock_news`)의 `stockai` 계정 (compose, 선택) |

> 운영 값은 서버의 `.env` 에 둡니다(`.env.prod.example`). → [deployment.md](deployment.md)

## 테스트

```bash
./gradlew test                                # 전체
./gradlew test --tests "ClassName"            # 특정 클래스
./gradlew test --tests "ClassName.methodName" # 특정 메서드
```

- **단위/통합**: JUnit 5 + Testcontainers(PostgreSQL) — 외부 DB 불필요.
- **동시성**: `LimitOrderMatchingServiceConcurrencyTest` — 동시 주문 매칭 시 데이터 정합성 검증.
  → [matching-engine.md](matching-engine.md)

## 데이터베이스 마이그레이션

- Flyway SQL: `src/main/resources/db/migration/` (현재 `V2`, `V6`~`V13`).
- 운영(`prod`)은 빈 DB 에서 엔티티로 테이블을 만들고, 엔티티로 표현되지 않는 `V2`·`V10` 과 `data.sql` 을 `spring.sql.init` 으로 돌립니다.
- Flyway **자동 실행은 비활성화**되어 있으며 초기화는 `global/config/FlywayConfig.java`가 담당합니다.
- 새 마이그레이션 추가 시 다음 버전 번호로 `V{n}__{설명}.sql` 파일을 생성합니다.

## 코드 컨벤션

- **Lombok**: `@Getter`, `@Builder`, `@NoArgsConstructor(access = PROTECTED)`, `@Slf4j`
- **DTO**: `{Action}Request` / `{Domain}Response` (`dto/` 패키지)
- **REST 경로**: `/api/{domain}`
- **검증**: 요청 바디에 JSR-303 `@Valid`
- **API 문서**: SpringDoc OpenAPI 애노테이션(`@Operation`, `@Tag`) → Swagger UI `/swagger-ui/`
