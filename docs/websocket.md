# WebSocket 설계

실시간 시세/호가 스트리밍과 KIS WebSocket 연동 구조를 설명합니다.
관련 코드는 `global/websocket`, `global/config/WebSocketConfig` 에 있습니다.

## 설계 모델: 서버 주도 스트리밍

클라이언트는 서버로 명령(메시지)을 보내지 않고 **구독만** 하며, 실시간 데이터(시세, 체결량 등)를
**받기만** 합니다. 서버는 클라이언트의 구독/해제 이벤트에 반응해 KIS 구독을 제어합니다.

통신 방식으로 WebSocket을 택한 이유:
- Polling — 잦은 체결 정보를 놓쳐 실시간 경험 제공이 어려움
- SSE — 서버→클라이언트 단방향
- WebSocket — 지속적 양방향, 헤더 오버헤드가 작아 HTTP보다 효율적

## STOMP 브로커 설정 (`WebSocketConfig`)

`@EnableWebSocketMessageBroker` 기반.

| 항목 | 값 |
|------|-----|
| 연결 엔드포인트 | `/ws` (SockJS 지원) |
| 클라이언트 구독 prefix | `/topic`, `/queue` (인메모리 SimpleBroker 브로드캐스트) |
| 서버 수신 prefix | `/app` |

시세 토픽은 종목별로 `/topic/stock/{종목코드}` 형태를 사용합니다.

## @MessageMapping 대신 @EventListener

클라이언트가 서버에 명령을 보내는 구조가 아니라 **구독에 반응해 서버 로직을 실행**하는 구조라,
`@MessageMapping`(STOMP 표준 명령 처리) 대신 STOMP 세션 이벤트를 구독합니다.
이로써 KIS 구독을 효율적으로 관리합니다.

## 구독 관리 (`WebSocketSubscriptionManager`)

종목별 구독자를 참조 카운팅으로 관리해 **첫 구독자일 때만 KIS에 구독**하고 **마지막 구독자가 나갈 때만
해제**합니다. 불필요한 외부 연결/구독을 줄이는 핵심입니다.

필드 (모두 `ConcurrentHashMap`):

| 필드 | 의미 |
|------|------|
| `viewerCounts: Map<String, AtomicInteger>` | 종목별 화면 구독자 수 |
| `orderReferenceCounts: Map<String, AtomicInteger>` | 종목별 주문 참조 수 |
| `sessionSubscriptions: Map<String, Set<String>>` | 세션ID → 구독 종목 집합 |
| `subscriptionIdToStockCode: Map<String, String>` | 구독ID → 종목코드 |

- 카운트 증감은 `AtomicInteger.incrementAndGet()` / `decrementAndGet()`로 원자 처리 (레이스 컨디션 방지).
- `hasActiveReason(stockCode)` — 화면 구독자 또는 주문 참조가 하나라도 있으면 구독 유지.
  즉 화면을 닫아도 미체결 주문이 있으면 시세 구독을 유지합니다.

## 세션 이벤트 처리 (`StockSubscriptionEventListener`)

`@EventListener`로 STOMP 세션 이벤트를 구독합니다.

- **`SessionSubscribeEvent`** — 구독자 수 증가, 첫 구독자면 KIS 구독 요청, 세션-종목 매핑 갱신
- **`SessionUnsubscribeEvent`** — 구독자 수 감소, 마지막 구독자면 KIS 구독 해제
- **`SessionDisconnectEvent`** — 세션이 구독하던 모든 종목을 정리, 각 종목이 0이 되면 KIS 해제

## KIS WebSocket 클라이언트 (`KisWebSocketClient`)

`TextWebSocketHandler`를 확장해 KIS 실시간 서버와 연결합니다.

- 접속 URL: `ws://ops.koreainvestment.com:21000`
- 처음 연결 시에만 세션을 열고 구독을 진행, 구독 종목은 동기화된 `Set`으로 추적
- **메시지 처리** (`handleTextMessage`):
  - 실시간 데이터 — 파이프 구분 (`0|...` / `1|...`) → 파싱 후 시세 DTO 생성
  - JSON 응답 — 구독 성공, 핑퐁 등
- **브로드캐스트** — `SimpMessagingTemplate.convertAndSend("/topic/stock/{code}", dto)`로 내부 브로커에 전달,
  `SimpleBrokerMessageHandler`가 해당 토픽 구독 세션들에 STOMP MESSAGE 프레임을 팬아웃
- **체결 이벤트 발행** — 체결 데이터를 파싱해 `LimitOrderFillEventMessage`를 발행 → 매칭 엔진과 연동
  ([matching-engine.md](matching-engine.md))
- **재연결** — 연결 끊김 시 `MAX_RECONNECT_ATTEMPTS`까지 지수적으로 재시도

## 브로드캐스트 플로우 (요약)

```
1. 클라이언트가 /topic/stock/{code} SUBSCRIBE
2. StockSubscriptionEventListener가 이벤트 수신 → 세션-종목 매핑 갱신
3. 첫 구독자일 때만 KisWebSocketClient가 KIS에 구독
4. KIS 콜백 메시지 → 파싱 → 시세 DTO
5. SimpMessagingTemplate.convertAndSend()
6. SimpleBrokerMessageHandler가 구독 세션들에 팬아웃
```

## 제약 / 참고

- KIS WebSocket은 하나의 토큰으로 **최대 100개 종목 구독** 가능 → 참조 카운팅으로 중복/불필요 구독을 줄여 대응.
- 동시성: 구독 상태는 `ConcurrentHashMap` + `AtomicInteger`로 관리(락프리).

## 관련 파일

- `global/config/WebSocketConfig.java` — STOMP 설정
- `global/websocket/manager/WebSocketSubscriptionManager.java` — 구독 참조 카운팅
- `global/websocket/manager/OrderSubscriptionCoordinator.java` — 주문 기반 구독 조정
- `global/websocket/listener/StockSubscriptionEventListener.java` — 세션 이벤트 처리
- `global/websocket/client/KisWebSocketClient.java` — KIS 연결·수신·브로드캐스트
- `global/websocket/monitor/WebSocketConnectionMonitor.java` — 연결 모니터링

> 참고: 이 문서는 코드 기준으로 작성되었습니다. 설계 배경 설명은
> [WebSocket 블로그](https://hwanheee.tistory.com/18)를 함께 참고하세요.
