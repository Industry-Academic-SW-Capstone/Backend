# stockIt

한국투자증권(KIS) Open API를 연동한 **한국 주식 모의투자 시뮬레이션 플랫폼**입니다.
실시간 시세·호가를 기반으로 Redis 매칭 엔진이 지정가/시장가 주문을 체결하며,
콘테스트·미션·랭킹·AI 종목 분석 등 게이미피케이션 요소를 제공합니다.

## 주요 기능

- **실시간 시세/호가** — KIS WebSocket 연동, STOMP over SockJS로 클라이언트에 브로드캐스트
- **주문 매칭 엔진** — Redis 기반 지정가 오더북 매칭, 이벤트 기반 체결 처리
- **자산·포트폴리오 관리** — 보유 종목, 현금, 손익 계산
- **콘테스트 / 미션 / 랭킹** — 모의투자 대회와 성과 랭킹(Caffeine 캐시)
- **AI 종목 분석** — Gemini 기반 기업 분석 및 보유 종목 기반 추천
- **인증** — Kakao OAuth + 로컬 로그인, JWT
- **알림** — Firebase FCM 푸시

## 기술 스택

| 구분 | 내용 |
|------|------|
| 언어 / 프레임워크 | Java 21, Spring Boot 3.5.6, Gradle |
| 데이터 | PostgreSQL 15, Redis 7, Flyway |
| 실시간 | Spring WebSocket + STOMP, WebFlux(WebClient) |
| 캐시 | Redis(분산) + Caffeine(로컬) + `@Cacheable` |
| 외부 연동 | KIS Open API, Kakao OAuth, Firebase FCM, Gemini |
| 인증 | Spring Security, JWT (jjwt) |
| 문서화 | SpringDoc OpenAPI (Swagger UI) |
| 모니터링 | Spring Actuator, Micrometer, Prometheus |
| 인프라 | Docker Compose, Traefik, Prometheus/Grafana |

## 아키텍처

패키지 루트: `grit.stockIt`

- **`domain/`** — 비즈니스 모듈. 각 모듈은 `controller / service / repository / dto / entity` 계층으로 구성
  - `matching` — Redis 기반 지정가 매칭 엔진 (핵심 트레이딩 로직)
  - `order` / `execution` — 주문 접수 및 체결 추적 (`LimitOrderFillEvent` 이벤트 기반)
  - `account` — 포트폴리오·현금 관리
  - `stock` — 종목 데이터 및 KIS API 연동 (`stock/analysis` — AI 분석)
  - `contest` / `ranking` — 대회 및 성과 랭킹
  - `member` / `auth` — 회원, Kakao OAuth, 로컬 인증
  - `notification` — FCM 푸시
  - `llm` — Gemini AI 기업 분석
  - `mission` / `title` / `industry` — 게이미피케이션 및 분류
- **`global/`** — 횡단 인프라 (config, jwt, exception, websocket)
- **`job/`** — 배치 작업 (KIS 마스터 파일 다운로드/파싱)

### 핵심 패턴

- **이벤트 기반** — 도메인 이벤트(`LimitOrderFillEvent` 등)로 주문 매칭 ↔ 체결/알림 디커플링
- **3계층 캐싱** — Redis(분산) + Caffeine(로컬 랭킹, 60s TTL) + `@Cacheable`
- **WebSocket** — `/ws`(SockJS), 토픽 `/topic`·`/queue`, 앱 prefix `/app`
- **JSON 컨벤션** — Jackson SNAKE_CASE (Java camelCase ↔ API snake_case 자동 변환)

## 빌드 & 실행

### Gradle

```bash
./gradlew build              # 전체 빌드 (테스트 포함)
./gradlew build -x test      # 테스트 제외 빌드
./gradlew clean build        # 클린 빌드
./gradlew test               # 테스트 실행
./gradlew bootRun            # 애플리케이션 실행
```

### Docker Compose

```bash
docker-compose up -d          # 전체 서비스 실행 (Traefik, PostgreSQL, Redis, Backend, 모니터링 스택)
docker-compose down           # 중지
docker-compose build          # 백엔드 이미지 재빌드
```

부하 테스트 (k6):

```bash
docker-compose --profile load-test run --rm k6 run /scripts/matching-engine-test.js
```

### Spring 프로파일

`local`(로컬 DB/Redis), `dev`(Docker 서비스, **기본값**), `prod`(외부 DB) 3종을 제공합니다.

```bash
-Dspring.profiles.active=local        # 또는
SPRING_PROFILES_ACTIVE=local
```

## 환경 변수

`.env` 파일에 설정합니다 (버전 관리에 커밋 금지):

| 변수 | 설명 |
|------|------|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_URL` | PostgreSQL 접속 정보 |
| `KIS_API_APPKEY` / `KIS_API_APPSECRET` | 한국투자증권 Open API 키 |
| `KAKAO_REST_API_KEY` / `KAKAO_REDIRECT_URI` | Kakao OAuth |
| `JWT_SECRET` | JWT 서명용 시크릿 |
| `GEMINI_API_KEY` / `CLAUDE_API_KEY` | AI 분석 |
| `DART_API_KEY` | DART 공시 데이터 |
| `PYTHON_ANALYSIS_URL` | 외부 Python 분석 서비스 URL |

> Firebase FCM 자격 증명 등 민감한 값도 동일하게 환경 변수/시크릿으로 주입합니다.

## API 문서

애플리케이션 실행 후 Swagger UI에서 확인할 수 있습니다.

- Swagger UI: `/swagger-ui/`
- OpenAPI 스펙: `/v3/api-docs`

## 데이터베이스 마이그레이션

Flyway SQL 스크립트는 `src/main/resources/db/migration/`에 있습니다 (V2, V6~V9).
Flyway 자동 실행은 비활성화되어 있으며 초기화는 `FlywayConfig.java`가 담당합니다.

## 테스트

- **단위/통합**: JUnit 5 + Testcontainers(PostgreSQL) — 외부 DB 불필요
- **동시성**: `LimitOrderMatchingServiceConcurrencyTest` — 동시 주문 매칭 시 데이터 정합성 검증
- **부하**: `k6/scripts/`의 k6 스크립트

## 배포

GitHub Actions가 Docker 이미지를 빌드·푸시한 뒤 SSH로 서버에 접속해
`docker-compose.prod.yml`로 실행합니다. `main` 브랜치 push 시 CD가 트리거됩니다.
시크릿은 GitHub Actions Secrets에 저장되어 배포 시 서버 환경변수로 주입됩니다.

> 파이프라인 단계·시크릿 목록·모니터링 구성 등 자세한 내용은 [docs/deployment.md](docs/deployment.md)를 참고하세요.

## 문서

- [docs/architecture.md](docs/architecture.md) — 패키지/도메인 구조, 이벤트 흐름, 캐싱
- [docs/matching-engine.md](docs/matching-engine.md) — 핵심 매칭 엔진 심화
- [docs/websocket.md](docs/websocket.md) — 실시간 WebSocket 스트리밍 설계
- [docs/deployment.md](docs/deployment.md) — 배포 런북, CI/CD, 모니터링
- [docs/development.md](docs/development.md) — 로컬 세팅, 테스트, 마이그레이션
- [AGENTS.md](AGENTS.md) — AI 코딩 에이전트용 프로젝트 가이드 (벤더 중립, `CLAUDE.md`는 이 파일을 가리킴)
