# 매칭 엔진

KIS 실시간 체결 틱에 맞춰 사용자 주문을 체결하는 엔진의 구조와 설계 결정을 설명합니다.
관련 코드는 `domain/matching`, `domain/order`, `domain/execution`, `domain/settlement`, `domain/account` 패키지에 있습니다.

## 한눈에

- **오더북 = 주문 테이블(`trade_order`)**. 미체결 잔량이 있는 `PENDING`·`PARTIALLY_FILLED` 주문이 곧 오더북이다. 따로 동기화할 사본이 없다.
- **종목마다 작성자가 하나(단일 작성자).** 오더북을 바꾸는 명령(체결·취소·만료)은 모두 종목 코드를 키로 Kafka `matching.commands` 에 들어가고,
  그 파티션을 맡은 워커 스레드 하나가 도착 순서대로 처리한다. 종목 락이 없다.
- **정산은 따로.** 체결 워커는 체결만 커밋하고, 계좌 반영은 계좌 키 큐(`settlement.requests`)의 정산 워커가 한다.

## 전체 흐름

```
[체결 틱]  KIS WebSocket → LimitOrderFillEventMessage → LimitOrderFillEventListener
[부하 진입] POST /api/test/mock-execution (prod 제외)
   └─ FillCommandPublisher (KafkaFillCommandPublisher)
        ├─ 현재가 Redis 갱신 (sim:price:last:{code})
        └─ FILL 명령 → matching.commands (key=종목) → 브로커 ack 대기(1초)

[취소]  POST /api/orders/{id}/cancel → OrderService.cancelOrder
        └─ 권한·상태 확인(400/403) → CANCEL 명령 → 202 접수 / 503
[만료]  MarketOrderExpiryScheduler (평일 15:40)
        └─ 미체결 시장가 주문마다 EXPIRE 명령

matching.commands  파티션 24 — 파티션당 워커 1 (MatchingCommandConsumer, 리스너 id·그룹 matching-fill)
   ├─ FILL   → LimitOrderFillCoordinator → LimitOrderExecutionService.fillOnce   (한 트랜잭션)
   │            워터마크 전진 → 후보 조회 → 배분 계획 → 주문 갱신·Execution 저장
   │            커밋 후 체결마다 정산 요청 → settlement.requests (key=계좌, 비동기)
   ├─ CANCEL → OrderCancelService.cancelOnce   (한 트랜잭션)
   └─ EXPIRE → OrderCancelService.expireOnce   워터마크 전진 → 상태 확인 → 취소 → 계좌 잠금 → 홀딩 해제

settlement.requests 파티션 24 — 워커 8 (SettlementRequestConsumer)
   └─ ExecutionSettlementService.settle   계좌 잠금 → 현금·보유·홀딩 반영 → Settlement 저장
```

## 왜 단일 작성자인가

오더북을 바꾸는 쪽이 여럿이면 같은 주문을 두고 경합한다(체결 도중 취소, 같은 주문에 두 틱이 배분 등).
경합을 락으로 막는 대신 **바꾸는 쪽을 하나로 만든다.**

- 체결·취소·만료가 같은 키(종목 코드)로 같은 파티션에 들어가므로, 한 종목의 명령은 한 줄로 도착 순서대로 처리된다.
  접수한 취소보다 먼저 도착한 체결이 주문을 채웠으면 취소는 아무것도 하지 않는다(거래소의 "Too late to cancel").
- 신규 주문 접수는 명령이 아니다. INSERT 만 하고, 커밋된 뒤에야 후보 조회에 보이므로 경합하지 않는다.
- 서로 다른 종목은 다른 파티션·다른 워커라 병렬로 처리된다. **인기 종목 하나의 처리량은 워커 하나가 정한다.**
- 파티션 수(24)는 키 → 파티션 매핑이다. 늘리면 같은 종목이 다른 파티션으로 가 순서가 깨지므로 처음에 정해 만든다(자동 생성 끔).

## 명령 (`MatchingCommand`)

| 종류 | 쓰는 필드 | 보내는 곳 |
|------|-----------|-----------|
| `FILL` | `eventId`, `orderMethod`, `price`, `quantity`, `eventTimestamp` (KIS 틱) | `KafkaFillCommandPublisher` |
| `CANCEL` | `orderId` | `OrderCommandPublisher.cancel` |
| `EXPIRE` | `orderId` | `OrderCommandPublisher.expire` |

- 공통: `type`, `stockCode`(파티션 키), `enqueuedAt`(큐 대기·지연 측정 기준).
- 전송은 `MatchingCommandSender` 가 브로커 기록 확인(ack)까지 기다린다(`matching.queue.ack-timeout`, 기본 1초).
  확인을 받지 못하면 **다시 보내지 않는다** — 새 offset 이 붙어 아래 워터마크로 거를 수 없는 중복이 된다.
- 워커는 `validate()` 로 종류별 필수 필드를 확인한다. 실패하면 재시도 없이 DLT 로 보낸다.

## 재전달 거르기 (워터마크)

Kafka 는 최소 한 번 전달이다. 처리 결과를 커밋한 뒤 offset 을 커밋하기 전에 워커가 죽으면 같은 명령이 다시 온다.

`consumer_watermark(topic, partition_no, last_offset)` 에 처리한 위치를 **명령 처리와 같은 트랜잭션**으로 기록한다
(`ConsumerWatermarkWriter.advance`).

```sql
INSERT INTO consumer_watermark (topic, partition_no, last_offset, updated_at)
VALUES (?, ?, ?, now())
ON CONFLICT (topic, partition_no) DO UPDATE
   SET last_offset = EXCLUDED.last_offset, updated_at = EXCLUDED.updated_at
 WHERE consumer_watermark.last_offset < EXCLUDED.last_offset
```

- 1행 = 처음 보는 위치 → 처리. 0행 = 이미 반영한 위치 → 건너뜀.
- 처리가 롤백되면 기록도 롤백돼 재시도 때 다시 처리된다. 맞는 주문이 없던 틱도 기록하므로 재전달 때 그사이 들어온 주문과 체결되지 않는다.
- 리밸런스 순간 두 워커가 같은 파티션을 쥐어도 이 행의 잠금이 둘을 한 줄로 세우고, 뒤에 온 쪽은 0행을 받는다.
- Kafka 문서의 "Storing Offsets Outside Kafka" 변형이다. `seek` 하지 않고 저장된 위치 이하를 건너뛴다. 전달은 최소 한 번, 반영은 한 번.
- 한계: 같은 내용을 **보내는 쪽이 두 번 넣으면** offset 이 달라 막지 못한다. 그래서 입구는 재전송하지 않는다.
  취소·만료는 상태로도 멱등하다(이미 취소·체결된 주문은 무시).
- 측정 회차 사이에 토픽을 다시 만들면 offset 이 0부터 시작하므로 워터마크도 비운다.

## 체결 (`LimitOrderExecutionService.fillOnce`)

- **후보 조회** (`OrderBookRepository.fetchMatchingEntries`, 네이티브 SQL):
  - 매수 틱 → 매도 주문 중 `price <= 체결가`, 낮은 가격 우선 / 매도 틱 → 매수 주문 중 `price >= 체결가`, 높은 가격 우선
  - 같은 가격이면 `created_at`, `order_id` 순(시간 우선). `matching.limit-order-fetch-size`(기본 100)건까지
  - 시장가 주문은 센티넬 가격(매수 999,999,999 / 매도 0.01)이라 항상 지정가보다 앞선다
- **배분** (`LimitOrderMatchPlanner`): 틱 수량을 우선순위대로 후보에 나눈다. 순수 로직이라 DB 를 보지 않는다.
- **반영**: 주문을 다시 읽어 잔여 수량으로 배분을 한 번 더 조이고 `applyFill`, `Execution` 저장. 전량 체결된 주문은 시세 구독을 해제한다.
- **계좌를 보지 않는다.** 체결 수량은 배분과 잔여 수량만으로 정해지므로 계좌 잠금이 종목의 한 줄 구간에 들어오지 않는다.
- 현금 확인은 주문 접수의 홀딩이 보장한다(아래). 체결 시점에 현금이 모자라 줄이는 일은 없다.

## 취소·만료 (`OrderCancelService`)

`cancelOnce`·`expireOnce` 는 같은 처리(`endOnce`)를 쓰고 로그 사유만 다르다.

1. 워터마크 전진 (0행이면 `DUPLICATE`)
2. 주문이 없거나 이미 `CANCELLED`·`FILLED` 면 `NOT_CANCELLABLE` — 아무것도 바꾸지 않는다
3. `markCancelled`
4. **계좌를 잠근 뒤**(`findByIdWithLock`) 홀딩 해제 — 같은 계좌의 정산과 한 줄로 서서 계좌 행 덮어쓰기를 막는다
   - 매수: `OrderHold` 에서 미정산 체결분을 뺀 나머지만 푼다(늦게 도착한 정산이 뺄 몫을 남긴다)
   - 매도: 잔여 수량만큼 보유 홀딩 수량을 푼다
5. 잔여 수량이 있으면 커밋 뒤 시세 구독 해제

- **취소 API는 접수만 한다.** 권한과 "이미 취소/체결"을 먼저 보고 400/403, 통과하면 명령을 넣고 202(`OrderCancelAcceptedResponse`),
  넣지 못하면 503. 최종 결과는 주문 상태로 확인한다.
- **만료**: 시장가 매수 홀딩은 그날 상한가로 잡으므로(`OrderPricingService.calculateMarketHoldAmount`) 다음 매매일로 넘어가면
  "홀딩 ≥ 체결금액" 보장이 깨진다. 장 마감 후 미체결 시장가 주문을 만료시킨다. 지정가는 대상이 아니다.
  스케줄러는 명령만 넣고, 넣지 못한 주문은 다음 실행 때 다시 대상이 된다.

## 주문 접수와 홀딩 (`OrderService`)

- **매수**: 현금을 빼지 않고 주문 금액만큼 홀딩(`Account.holdAmount` + `OrderHold`). 지정가는 지정가 × 수량, 시장가는 당일 상한가 × 수량(올림).
- **매도**: 보유 주식 수량을 홀딩(`AccountStock.holdQuantity`).
- 계좌는 `findByIdWithLock` 으로 잠근다. 연관(회원·대회)을 조인하지 않는다 — PostgreSQL 은 잠글 테이블을 명시하지 않은 행 잠금을
  FROM 절의 모든 테이블에 걸어, 조인하면 참가자 전원이 공유하는 대회 행까지 잠긴다.
- 커밋 후(`TransactionHandler.afterCommit`) 시세 구독을 등록한다.

## 정산 (`ExecutionSettlementService`)

- 체결 워커가 체결을 커밋한 뒤 체결마다 `SettlementRequest(executionId, accountId, filledAt)` 를 계좌 키로 보낸다.
  **ack 를 기다리지 않는다** — 기다리면 그 시간이 종목의 한 줄 구간에 들어간다. 잃어도 체결이 DB 에 있어 복구 배치가 정산한다.
- 정산 워커(`settlement.queue.concurrency`, 기본 8)는 계좌를 잠그고 현금·보유·홀딩을 반영한 뒤 `Settlement` 를 저장한다.
  `settlement.execution_id` 유니크가 중복 정산의 방어선이다(두 번 와도 한 번만 반영).
- **복구 배치** (`UnsettledExecutionScheduler`, `settlement.recovery.interval` 기본 60초): 정산 없이 남은 체결을 찾아 정산한다.
  방금 만든 체결은 `grace-period`(30초) 동안 건드리지 않는다. 스케줄링이 꺼진 환경(스테이징 측정)에서는 돌지 않는다.

## 실패 처리 (`KafkaConfig`)

| 대상 | 일시 실패 | 처리할 수 없는 메시지 |
|------|-----------|------------------------|
| `matching.commands` | **무한 재시도** (200ms → 5s 지수 백오프). 건너뛰면 유실되고 종목 안의 순서가 깨진다. 그동안 파티션이 멈추므로 lag 으로 드러난다 | 역직렬화 실패·`InvalidMatchingCommandException` → `matching.commands.DLT` |
| `settlement.requests` | 10회 재시도 후 DLT. 계좌 하나 때문에 파티션이 멈추지 않게 하고, 건너뛴 건은 복구 배치가 정산 | `InvalidSettlementRequestException` → `settlement.requests.DLT` |

- 에러 핸들러 빈(`DefaultErrorHandler`)은 하나만 둔다. 둘이면 Boot 가 기본 팩토리에 어느 쪽도 붙이지 않는다. 정산은 전용 컨테이너 팩토리를 쓴다.
- 역직렬화 실패는 `ErrorHandlingDeserializer` 가 감싸 원래 바이트 그대로 DLT 에 남긴다.

## 커넥션 예산

워커 스레드는 처리하는 동안 커넥션을 하나씩 쥔다. 스테이징 풀 60 = 체결 워커 24 + 정산 워커 8 + 비동기(미션·알림) 4 + 복구 배치 1 + HTTP 23 이상.
워커 수를 바꾸면 풀도 함께 맞춘다.

## 지표

| 이름 | 뜻 |
|------|----|
| `matching.enqueue{result=ok\|fail}` | 체결 명령을 큐에 넣은 수 / 넣지 못한 수(유실) |
| `matching.fill.queue.wait` | 큐에 넣은 뒤 워커가 꺼낼 때까지 |
| `matching.fill.e2e` | 큐에 넣은 뒤 체결이 커밋될 때까지 |
| `matching.fill.duplicate` | 워터마크로 건너뛴 체결 명령 수(재전달) |
| `settlement.e2e` | 체결 커밋 → 정산 커밋 |

워커 처리 시간·횟수·실패는 `spring.kafka.listener`(리스너 id 가 `name` 태그), 전송 ack 는 `spring.kafka.template`,
lag 과 DLT 적재 수는 kafka-exporter 로 본다. 대시보드는 `monitoring/grafana/dashboards/mixed-load-dashboard.json`.

## 테스트

- `LimitOrderExecutionServiceTest` — 배분·우선순위·부분 체결 (단위)
- `LimitOrderFillCoordinatorConcurrencyTest` — 재전달 건너뜀, 주문 없던 틱의 재전달, 같은 위치를 두 워커가 동시에 처리(좀비)
- `ConsumerWatermarkWriterTest` — 앞으로만 전진, 파티션별, 롤백 시 미기록
- `OrderCancelQueryCharacterizationTest` / `MarketOrderExpiryTest` — 접수(400/403/503)와 워커 취소·만료의 홀딩 해제
- `MatchingQueueFlowIntegrationTest` (Testcontainers Kafka) — 입구 → 체결 → 정산, 취소 뒤 체결은 체결되지 않음, 만료
- `MatchingCommandConsumerIntegrationTest` — 위치 기록, 독 메시지 DLT 후에도 파티션이 계속 흐름

## 관련 파일

- `domain/matching/queue/` — 명령, 전송(`MatchingCommandSender`), 입구(`KafkaFillCommandPublisher`, `OrderCommandPublisher`), 워커(`MatchingCommandConsumer`), 지표
- `domain/matching/service/LimitOrderExecutionService.java` — 체결 트랜잭션
- `domain/matching/service/LimitOrderMatchPlanner.java` — 배분 계획
- `domain/matching/repository/OrderBookRepository.java` — 후보 조회
- `domain/matching/repository/ConsumerWatermarkWriter.java` — 워터마크
- `domain/order/service/OrderService.java` — 주문 접수·홀딩·취소 접수
- `domain/order/service/OrderCancelService.java` — 워커의 취소·만료
- `domain/order/scheduler/MarketOrderExpiryScheduler.java` — 만료 명령 발행
- `domain/settlement/` — 정산 큐·워커·복구 배치
- `global/config/KafkaConfig.java` — 실패 처리·DLT
- `src/main/resources/kafka.yml` — 프로듀서·컨슈머 설정
- `docker-compose.yml`(로컬) / `docker-compose.staging-kafka.yml`(스테이징) — 브로커와 토픽
