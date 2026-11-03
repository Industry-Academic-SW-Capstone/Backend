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
./gradlew :app:bootRun       # 네 서비스를 한 프로세스로 실행 (포트 8080)

# 서비스별로 따로 실행 — 각자 프로필 <서비스> 가 더해진다(application-<서비스>.yml)
./gradlew :market:bootRun    # 시세   8091
./gradlew :exchange:bootRun  # 거래소 8092 (내부 API 켬)
./gradlew :broker:bootRun    # 증권사 8093 (내부 API 켬)
./gradlew :extra:bootRun     # 부가   8094 (내부 API 켬)
```

서비스별로 띄우면 서비스 사이 동기 조회(`*QueryClient`)가 HTTP 로 간다. 묻는 쪽은 `stockit.services.*-url`
(환경변수 `MARKET_URL`·`EXCHANGE_URL`·`BROKER_URL`·`EXTRA_URL`, 기본 localhost 위 포트)로 부르고, 답하는 쪽은
`stockit.internal-api.enabled=true` 일 때만 `/internal/**` 를 연다. 한 프로세스(app)에서는 내부 API 가 꺼져 있고 같은 프로세스 구현을 바로 쓴다.

Docker Compose:

```bash
docker-compose up -d          # Traefik, PostgreSQL, Redis, Kafka, Backend, 모니터링
docker-compose down
docker-compose build          # 백엔드 이미지 재빌드
```

## Spring 프로파일

| 프로파일 | 용도 |
|----------|------|
| `local` | 로컬 DB/Redis/Kafka (localhost, Kafka 는 `docker-compose up -d kafka kafka-init`) |
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

- **단위/통합**: JUnit 5 + Testcontainers(PostgreSQL, Kafka) — 외부 DB·브로커 불필요. 워커는 `KafkaIntegrationTestSupport` 를 상속한 테스트에서만 뜬다.
- **매칭**: `LimitOrderFillCoordinatorConcurrencyTest`(재전달·좀비 워커), `MatchingQueueFlowIntegrationTest`(체결 → 정산, 취소·만료 순서).
  → [matching-engine.md](matching-engine.md)
- **부하(k6)**:
  ```bash
  docker-compose --profile load-test run --rm k6 run /scripts/matching-engine-test.js
  ```
  스크립트는 저장소에 없습니다. `k6/`와 `benchmark/`는 개인 측정용이라 `.gitignore`에 들어 있으니 로컬에 두고 씁니다.

## 데이터베이스 마이그레이션

- Flyway SQL: `common/src/main/resources/db/migration/` (현재 `V2`, `V6`~`V20`).
- Flyway **자동 실행은 비활성화**되어 있으며 초기화는 `global/config/FlywayConfig.java`가 담당합니다.
- 새 마이그레이션 추가 시 다음 버전 번호로 `V{n}__{설명}.sql` 파일을 생성합니다.
- **서비스별 schema**(`V20`): 같은 database 안에서 `market`·`exchange`·`broker`·`extra` schema 로 나뉜다. 테이블 이름만 쓴 SQL 은
  커넥션의 search_path(`stockit.db.search-path`, 서비스 프로필이 자기 schema 로 정함)로, JPA 는 `hibernate.default_schema` 로 자기 schema 에 간다.
  서비스별 계정은 `db/service-roles.sql`(관리자로 한 번 실행, 비밀번호는 psql 변수). 테스트와 로컬 app 실행은 public 그대로다.
- 미션·보상·칭호 시드(`extra` 의 `data.sql`)는 부가의 관리자 API `POST /api/admin/missions/seed` 로 반영한다(upsert).

## 코드 컨벤션

- **Lombok**: `@Getter`, `@Builder`, `@NoArgsConstructor(access = PROTECTED)`, `@Slf4j`
- **DTO**: `{Action}Request` / `{Domain}Response` (`dto/` 패키지)
- **REST 경로**: `/api/{domain}`
- **검증**: 요청 바디에 JSR-303 `@Valid`
- **API 문서**: SpringDoc OpenAPI 애노테이션(`@Operation`, `@Tag`) → Swagger UI `/swagger-ui/`
