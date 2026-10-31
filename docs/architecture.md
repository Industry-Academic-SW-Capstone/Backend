# 아키텍처

stockIt의 패키지 구조, 도메인 책임, 이벤트 흐름, 캐싱 전략을 설명합니다.
빠른 개요는 [AGENTS.md](../AGENTS.md)를, 실행/빌드는 [development.md](development.md)를 참고하세요.

## 패키지 구조 (`grit.stockIt`)

```
contract/   서비스 사이 메시지 계약 (거래소 통보 ExecutionReport, 토픽 이름) — 필드는 추가만 한다
domain/     비즈니스 모듈 (controller / service / repository / dto / entity)
global/     횡단 인프라 (config, jwt, exception, websocket, auth, common, util)
job/        배치 작업 (KIS 마스터 파일 다운로드/파싱)
```

### 도메인 모듈

| 모듈 | 책임 |
|------|------|
| `matching` | 거래소 — 오더북(`exchange_order`)·체결 원장, Kafka 명령 큐와 종목 파티션 워커, 취소·만료, 시장가 당일 만료 스케줄러 → [matching-engine.md](matching-engine.md) |
| `order` | 주문 접수·검증·홀딩(현금/주식), 주문·취소 명령(아웃박스), 취소·만료·거부 통보로 홀딩 해제 |
| `execution` | 증권사의 체결 내역(거래소 통보로 기록) |
| `settlement` | 정산 워커 (거래소 통보로 체결 내역·주문·계좌 반영) |
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
- `outbox/` — 아웃박스(업무 트랜잭션에 메시지를 함께 적고 릴레이가 Kafka 로 보냄)
- `jwt/` — JWT 필터 및 토큰 서비스. 토큰에 회원 번호(`mid`)와 이메일을 싣고, 필터는 회원 테이블을 읽지 않고 토큰만으로
  `LoginMember`(principal)를 세웁니다. 회원 번호가 필요한 곳은 `@AuthenticationPrincipal LoginMember` 또는 `LoginMember.current()`
- `exception/` — `GlobalExceptionHandler` + 커스텀 예외(`BadRequestException`, `ForbiddenException`, `UntradeableStockException`)
- `websocket/` — KIS WebSocket 클라이언트, STOMP 브로커, 구독 관리

## 이벤트 기반 아키텍처

도메인 이벤트로 주문 매칭과 체결/알림을 디커플링합니다.

- **`LimitOrderFillEventMessage`** — KIS 체결 틱. `LimitOrderFillEventListener`(@EventListener)가 받아
  체결 명령을 Kafka `matching.commands` 에 넣습니다. 상세 흐름은 [matching-engine.md](matching-engine.md).
- **회원 이벤트 (`contract.MemberEvent`, Kafka `member.events`, 키 = 회원)** — 증권사가 회원에게 일어난 일(거래 완료, 체결,
  가입, 랭커 달성, AI 분석 완료)을 부가 서비스에 알립니다. 트랜잭션 안의 일은 `MemberEventOutbox` 로 아웃박스에 적어 커밋된 것만
  나가고, AI 분석 완료만 바로 보냅니다. 미션(`MissionEventConsumer`, 그룹 `mission`)과 알림(`NotificationEventConsumer`,
  그룹 `notification`)이 각자 받아, 처리 기록(`processed_event`)으로 같은 이벤트를 한 번만 처리합니다. 실패는 재시도 후 DLT.
- **부가 이벤트 (`contract.ExtraEvent`, Kafka `extra.events`, 키 = 회원)** — 부가 서비스(미션)가 증권사에 보상 예수금 지급을
  요청하고(`REWARD_GRANTED`) 티어 변화를 알립니다(`TIER_CHANGED`, 티어가 바뀔 때만). 미션 트랜잭션에서 `ExtraEventOutbox` 로
  아웃박스에 적습니다. 증권사 `ExtraEventConsumer`(그룹 `broker`)가 기본 계좌를 잠그고 지급하거나 회원 테이블의 티어 복사본을
  바꾸며, 같은 이벤트는 `processed_event` 로 한 번만 처리합니다. 랭킹은 이 복사본(`member.tier`)을 읽습니다.
- **서비스 사이 동기 조회 (`contract.BrokerQueryClient`, `contract.ExtraQueryClient`)** — 지금 답이 필요한 드문 조회만 둡니다.
  부가 → 증권사: 파산 신청 자산, 보유 일수 배치의 보유 회원(묶음으로), 전체 발송 대상 회원. 증권사 → 부가: 대표 칭호 장착 시
  칭호 존재·보유 여부, 프로필의 보유 칭호. 구현(`LocalBrokerQueryClient`, `LocalExtraQueryClient`)은 지금 같은 프로세스 호출입니다.
- **`MissionCompletedEvent`** — 부가 서비스 안의 메모리 이벤트. 미션 트랜잭션이 커밋된 뒤 같은 스레드에서 알림을 보냅니다.

## 캐싱 전략 (3계층)

| 계층 | 용도 | 설정 |
|------|------|------|
| Redis (분산) | 시세(현재가), 세션성 데이터 | `RedisConfig` |
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
