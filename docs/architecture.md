# 아키텍처

stockIt의 패키지 구조, 도메인 책임, 이벤트 흐름, 캐싱 전략을 설명합니다.
빠른 개요는 [AGENTS.md](../AGENTS.md)를, 실행/빌드는 [development.md](development.md)를 참고하세요.

## 패키지 구조 (`grit.stockIt`)

```
domain/     비즈니스 모듈 (controller / service / repository / dto / entity)
global/     횡단 인프라 (config, jwt, exception, websocket, auth, common, util)
job/        배치 작업 (KIS 마스터 파일 다운로드/파싱)
```

### 도메인 모듈

| 모듈 | 책임 |
|------|------|
| `matching` | Redis 기반 지정가 매칭 엔진 (핵심 트레이딩 로직) → [matching-engine.md](matching-engine.md) |
| `order` | 주문 접수·검증·홀딩(현금/주식), Redis 오더북 등록 |
| `execution` | 체결 기록 및 추적 |
| `account` | 자산·포트폴리오·현금 관리 |
| `stock` | 종목 데이터, KIS API 연동, 차트, `stock/analysis`(AI 분석) |
| `contest` | 모의투자 대회 |
| `ranking` | 성과 랭킹 (Caffeine 캐시, 60s TTL) |
| `member` / `auth` | 회원, Kakao OAuth, 로컬 인증 |
| `notification` | FCM 푸시 알림 |
| `llm` | Gemini AI 기업 분석 |
| `mission` / `title` / `industry` | 게이미피케이션 및 분류 |
| `test` | 로드 테스트/더미 데이터용 개발 편의 API |

### 공통 인프라 (`global/`)

- `config/` — Security, WebSocket(STOMP), Redis, JPA, Cache, Flyway, Swagger, 외부 API properties
- `jwt/` — JWT 필터 및 토큰 서비스
- `exception/` — `GlobalExceptionHandler` + 커스텀 예외(`BadRequestException`, `ForbiddenException`, `UntradeableStockException`)
- `websocket/` — KIS WebSocket 클라이언트, STOMP 브로커, 구독 관리

## 이벤트 기반 아키텍처

도메인 이벤트로 주문 매칭과 체결/알림을 디커플링합니다.

- **`LimitOrderFillEventMessage`** — 지정가 체결 트리거. `LimitOrderEventPublisher`(@EventListener)가
  수신해 Redis 큐에 적재하고 매칭을 구동합니다. 상세 흐름은 [matching-engine.md](matching-engine.md).
- **`TradeCompletionEvent`** — 거래 완료 후 후속 처리(미션/알림 등) 트리거.
- 미션·알림 도메인도 각자 `event/` 패키지에서 이벤트를 발행/구독합니다.

## 캐싱 전략 (3계층)

| 계층 | 용도 | 설정 |
|------|------|------|
| Redis (분산) | 오더북, 시세, 매칭 큐/락, 세션성 데이터 | `RedisConfig` |
| Caffeine (로컬) | 랭킹 (`rankings` 캐시, TTL 60초) | `CacheConfig` |
| `@Cacheable` | 메서드 단위 캐싱 | Spring Cache 추상화 |

## WebSocket / STOMP

- 연결 엔드포인트: `/ws` (SockJS)
- 클라이언트 구독 prefix: `/topic`, `/queue` (SimpleBroker 브로드캐스트)
- 서버 수신 prefix: `/app`
- 설정: `global/config/WebSocketConfig.java`
- KIS 실시간 시세는 `global/websocket/client/KisWebSocketClient`가 수신하며,
  구독 관리는 `WebSocketSubscriptionManager` / `OrderSubscriptionCoordinator`가 담당합니다.

> 서버 주도 스트리밍 모델, 구독 참조 카운팅, 브로드캐스트 흐름 등 상세는 [websocket.md](websocket.md).

## JSON 컨벤션

Jackson `SNAKE_CASE` 전략 — Java camelCase 필드가 API 응답에서 snake_case로 자동 변환됩니다.

## REST 규약

- 경로: `/api/{domain}` (예: `/api/orders`, `/api/stocks`, `/api/rankings`)
- DTO: `{Action}Request` / `{Domain}Response`
- 검증: JSR-303 `@Valid`
- 문서: SpringDoc OpenAPI (`@Operation`, `@Tag`) → Swagger UI `/swagger-ui/`
