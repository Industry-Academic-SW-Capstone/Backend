# AGENTS.md

이 파일은 이 저장소에서 작업하는 AI 코딩 에이전트(Claude Code, Cursor, Copilot 등)를 위한 가이드입니다. 벤더 중립적인 단일 진실 공급원(source of truth)이며, 도구별 파일(예: `CLAUDE.md`)은 이 문서를 가리키기만 합니다.

## 프로젝트 개요

stockIt은 Spring Boot로 구축된 한국 주식 거래 시뮬레이션 플랫폼입니다. 한국투자증권(KIS) API와 연동해 실시간 주식 데이터를 제공하고, WebSocket 기반 실시간 호가창을 지원하며, 대회·미션·랭킹·AI 기반 종목 분석(Gemini) 같은 기능을 포함합니다.

## 상세 문서 (필요 시 참고)

이 파일은 항상 로드되는 오리엔테이션 문서입니다. 깊은 내용은 필요할 때만 해당 `docs/` 파일을 읽으세요:

- `docs/architecture.md` — 패키지/도메인 구조, 이벤트 흐름, 캐싱
- `docs/matching-engine.md` — Redis 오더북 매칭 내부 구조 (오더북 키, 분산 락, 계좌 비관적 락, 이중 쓰기 정합성, 복구 배치, 동시성)
- `docs/websocket.md` — 실시간 스트리밍 설계 (STOMP 설정, 구독 참조 카운팅, KIS WebSocket 클라이언트, 브로드캐스트 흐름)
- `docs/deployment.md` — CI/CD 파이프라인, Docker Hub, SSH + `docker-compose.prod.yml`, 시크릿, 모니터링
- `docs/development.md` — 로컬 설정, 프로파일, 테스트, 마이그레이션
- `docs/conventions.md` — **코드 작성 전 필독**: 네이밍 규칙(DTO 접미사, record), 길이 가이드라인, Spring/JPA 규칙, Checkstyle/ArchUnit/JaCoCo 사용법과 baseline

### `docs/`에 무엇을 넣는가

`docs/`는 **다음 작업자가 작업 전에 참고할 상시 문서**만 담습니다. 현재는 위 6개입니다. 새 파일을 만들기 전에 그 문서가 6개월 뒤에도 유효한지 자문하고, 추가한다면 위 목록에도 함께 올리세요.

**넣지 마세요:**

- **특정 PR·작업의 변경 기록** — 리팩토링 요약, 마이그레이션 후기, 작업 로그. 시간이 지나면 코드와 어긋나고, 읽는 사람에게 현재 상태를 알려주지 못합니다. 정본은 **PR 본문**과 git 이력입니다
- **사용자 판단이 필요한 미결 항목** — 발견한 결함, 보류한 결정, 후속 제안. 애초에 저장소에 쌓지 마세요. 저장소는 **합의된 현재 상태**를 담는 곳이지 미결 목록을 담는 곳이 아닙니다

작업 중 발견한 결함·편차·판단 요청은 **PR 본문에 적습니다.** 리뷰어가 PR 하나만 열면 다 보이고, 머지되면 그 시점의 기록으로 굳고, 저장소에는 남지 않습니다.

## 빌드 & 실행 명령

```bash
# 빌드
./gradlew build              # 테스트 포함 전체 빌드
./gradlew build -x test      # 테스트 제외 빌드
./gradlew clean build        # 클린 빌드

# 테스트
./gradlew test                                    # 전체 테스트 실행
./gradlew test --tests "ClassName"                 # 특정 테스트 클래스 실행
./gradlew test --tests "ClassName.methodName"      # 특정 테스트 메서드 실행

# Docker (개발)
docker-compose up -d          # 전체 서비스 시작 (Traefik, PostgreSQL, Redis, Backend)
docker-compose down           # 전체 서비스 중지
docker-compose build          # 백엔드 이미지 재빌드

# 부하 테스트 (k6)
docker-compose --profile load-test run --rm k6 run /scripts/matching-engine-test.js
```

## 아키텍처

**Java 21 / Spring Boot 3.5.6 / Gradle**, PostgreSQL 15, Redis 7, Flyway 마이그레이션 사용.

### 패키지 구조 (`grit.stockIt`)

- **`domain/`** — 비즈니스 모듈. 각 모듈은 controller/service/repository/dto/entity 계층으로 구성:
  - `matching` — Redis 기반 지정가 주문 매칭 엔진 (핵심 거래 로직)
  - `order` / `execution` — 주문 접수 및 체결 추적, 이벤트 기반 처리(`LimitOrderFillEvent`)
  - `account` — 포트폴리오 및 현금 관리
  - `stock` — 주식 데이터 및 KIS API 연동
  - `contest` — 거래 대회
  - `ranking` — 성과 랭킹 (Caffeine 캐시, 60초 TTL)
  - `member` / `auth` — 사용자 계정, 카카오 OAuth, 로컬 인증
  - `notification` — FCM 푸시 알림
  - `llm` — Gemini AI 기업 분석
  - `mission` / `title` / `industry` — 게이미피케이션 및 분류

- **`global/`** — 횡단 관심사(cross-cutting) 인프라:
  - `config/` — Security, WebSocket(STOMP), Redis, JPA, Cache, Flyway, Swagger, 외부 API 프로퍼티
  - `jwt/` — JWT 필터 및 토큰 서비스
  - `exception/` — GlobalExceptionHandler와 커스텀 예외(BadRequestException, ForbiddenException, UntradeableStockException)
  - `websocket/` — KIS WebSocket 클라이언트, STOMP 브로커, 구독 관리

- **`job/`** — 배치 작업 (KIS 마스터 파일 다운로드/파싱)

### 핵심 아키텍처 패턴

- **이벤트 기반**: 도메인 이벤트(예: `LimitOrderFillEvent`)로 주문 매칭과 체결/알림을 디커플링
- **3단계 캐싱**: Redis(분산) + Caffeine(로컬 랭킹) + `@Cacheable`
- **WebSocket**: `/ws`에서 SockJS 위 STOMP, 토픽은 `/topic`·`/queue`, 앱 prefix는 `/app`
- **JSON 컨벤션**: Jackson SNAKE_CASE — Java camelCase가 API 응답에서 snake_case로 자동 변환
- **모니터링**: Spring Actuator + Micrometer → Prometheus 메트릭; PostgreSQL 슬로우 쿼리 모니터링은 `pg_stat_statements` 사용(`monitoring/` 참고)

## 환경 & 프로파일

세 가지 Spring 프로파일: `local`(로컬 DB/Redis), `dev`(Docker 서비스, **기본값**), `prod`(외부 DB). 활성 프로파일은 `SPRING_PROFILES_ACTIVE`를 통해 기본적으로 `dev`로 설정됩니다.

필수 환경변수(`.env`에 설정):
- `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_URL`
- `KIS_API_APPKEY`, `KIS_API_APPSECRET`
- `KAKAO_REST_API_KEY`, `KAKAO_REDIRECT_URI`
- `JWT_SECRET`, `GEMINI_API_KEY`, `CLAUDE_API_KEY`, `DART_API_KEY`, `PYTHON_ANALYSIS_URL`
- `APP_CORS_ALLOWED_ORIGINS` — CORS 허용 오리진(쉼표 구분). 로컬은 기본값으로 충분하지만 **배포 시 미설정이면 애플리케이션이 기동에 실패합니다(fail-fast)**
- `APP_ADMIN_EMAILS` — 관리자 API(`/api/admin/**`, `/api/batch-jobs/**`) 허용 이메일(쉼표 구분). 비어 있으면 아무도 통과하지 못합니다(fail-closed)

> 프로덕션 전용 시크릿(예: `KAKAO_CLIENT_SECRET`, `FIREBASE_CREDENTIALS_BASE64`)은 로컬 `.env`가 아니라 배포 시점에 GitHub Actions Secrets와 Docker Compose 환경변수로 주입됩니다. `docs/deployment.md` 참고.

## 테스트

- **단위/통합**: JUnit 5 + Testcontainers(PostgreSQL) — 외부 DB 불필요
- **동시성 테스트**: `LimitOrderMatchingServiceConcurrencyTest`가 동시 주문 매칭 하에서 데이터 정합성을 검증
- **부하 테스트**: `/k6/scripts/`의 k6 스크립트 (주문 API, 체결, 통합 워크플로)

## 데이터베이스 마이그레이션

Flyway SQL 스크립트는 `src/main/resources/db/migration/`에 있습니다(V2, V6–V9). Flyway 자동 실행은 비활성화돼 있으며, 초기화는 `FlywayConfig.java`가 담당합니다.

## CI/CD

GitHub Actions(`.github/workflows/ci-cd.yml`): JDK 21 Temurin으로 빌드하고, Docker 이미지를 `${DOCKER_USERNAME}/stockit`에 푸시하며(태그: 브랜치 ref, `<branch>-<sha>`, 기본 브랜치에서는 `latest`), SSH로 배포합니다. CD는 `main` 브랜치 푸시 시 트리거됩니다.

## 코드 컨벤션

전체 규칙은 `docs/conventions.md`에 있습니다 — **코드 작성·이름 변경 전 반드시 읽으세요**. 위반 시 빌드가 실패합니다(`./gradlew build`에서 Checkstyle + ArchUnit 실행). 핵심 사항:

- **Lombok**: `@Getter`, `@Builder`, `@NoArgsConstructor(access = PROTECTED)`, `@Slf4j`; 생성자 주입만 사용(`@RequiredArgsConstructor`, 필드 `@Autowired` 금지); 엔티티에 `@Setter` 금지
- **DTO**: `dto/` 패키지에 `{Action}Request` / `{Domain}Response`; Java record 선호; `Dto` 접미사 절대 금지. DTO **필드** 이름 변경은 API 계약을 바꿉니다(전역 SNAKE_CASE Jackson) — 클래스 이름 변경은 안전하지만 필드 이름 변경은 프론트엔드 협의 필요
- **아키텍처**: 컨트롤러는 레포지토리를 직접 참조하면 안 됩니다(서비스를 경유)
- **REST 경로**: `/api/{domain}` (예: `/api/members`, `/api/orders`)
- **검증**: 요청 바디에 JSR-303 `@Valid`
- **API 문서**: SpringDoc OpenAPI 애노테이션(`@Operation`, `@Tag`) — Swagger UI는 `/swagger-ui/`
- **Baseline**: 기존 위반은 억제돼 있습니다(`config/checkstyle/suppressions.xml`, `src/test/resources/archunit_store/`) — 여기에 신규 항목을 절대 추가하지 마세요
