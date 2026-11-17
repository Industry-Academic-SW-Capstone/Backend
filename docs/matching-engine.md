# 매칭 엔진

KIS 실시간 체결 틱에 맞춰 사용자 주문을 체결하는 엔진의 구조와 설계 결정을 설명합니다.
증권사(주문·정산)와 거래소(오더북·체결)를 나눠, 둘은 Kafka 메시지로만 주고받습니다.
관련 코드는 거래소가 `domain/matching`, 증권사가 `domain/order`, `domain/execution`, `domain/settlement`, `domain/account`, 공통 계약이 `contract` 에 있습니다.

## 한눈에

- **오더북은 거래소의 것(`exchange_order`)이다.** 증권사는 주문을 자기 원장(`trade_order`)에 저장하고 홀딩한 뒤 ORDER 명령을 보내고,
  거래소가 받아 오더북에 올린다. 두 서비스가 같은 테이블을 쓰지 않는다.
- **종목마다 작성자가 하나(단일 작성자).** 오더북을 바꾸는 명령(주문·체결·취소·만료)은 모두 종목 코드를 키로 Kafka `matching.commands` 에 들어가고,
  그 파티션을 맡은 워커 스레드 하나가 도착 순서대로 처리한다. 종목 락이 없다.
- **결과는 통보로 돌아온다.** 거래소는 결과(접수·거부·체결·취소·만료)를 그 트랜잭션 안에서 아웃박스에 적고, 릴레이가 계좌 키 통로
  `exchange.reports` 로 보낸다. 증권사의 정산 워커가 받아 자기 주문·체결 내역·계좌를 고친다(FIX 의 Execution Report 와 같은 모양).
- **계좌를 고치는 것은 증권사뿐이다.** 거래소는 계좌를 모른다.

## 전체 흐름

```
[주문]  POST /api/orders/... → OrderService (한 트랜잭션)
        계좌 잠금 → 홀딩 → trade_order 저장(PENDING) → 아웃박스에 ORDER 명령 → 201
[취소]  POST /api/orders/{id}/cancel → OrderService.cancelOrder
        권한·상태 확인(400/403) → 아웃박스에 CANCEL 명령 → 202 접수
        (주문과 같은 아웃박스라 적힌 순서대로 나간다 — 취소가 주문보다 먼저 거래소에 닿지 않는다)
[체결 틱]  KIS WebSocket → LimitOrderFillEventMessage → LimitOrderFillEventListener
[부하 진입] POST /api/test/mock-execution (prod 제외)
   └─ FillCommandPublisher (KafkaFillCommandPublisher)
        ├─ 현재가 Redis 갱신 (sim:price:last:{code}, 계약 MarketPriceCache — 증권사의 랭킹·종목 상세도 이걸로 읽는다)
        └─ FILL 명령 → matching.commands (key=종목) → 브로커 ack 대기(1초)
[만료]  거래소 MarketOrderExpiryScheduler (평일 15:40) — 오더북의 미체결 시장가 주문마다 EXPIRE 명령

matching.commands  파티션 24 — 파티션당 워커 1 (MatchingCommandConsumer, 리스너 id·그룹 matching-fill)
   ├─ ORDER  → OrderAcceptService.acceptOnce   exchange_order 에 넣음 → ACCEPTED (가격·수량이 틀리면 REJECTED)
   ├─ FILL   → LimitOrderFillCoordinator → LimitOrderExecutionService.fillOnce
   │            후보 조회 → 배분 → exchange_order 갱신·exchange_fill 저장 → FILL 통보
   ├─ CANCEL → OrderCancelService.cancelOnce   exchange_order 닫음 → CANCELED 통보
   └─ EXPIRE → OrderCancelService.expireOnce   〃 → EXPIRED 통보
   (모두 한 트랜잭션: 워터마크 전진 + 오더북 변경 + 통보를 아웃박스에)

OutboxRelay — outbox_message 를 id 순서대로 Kafka 로 보내고 지움 (증권사의 ORDER·CANCEL, 거래소의 통보 모두)

exchange.reports  파티션 24 — 워커 8 (ExecutionReportConsumer, 리스너 id·그룹 settlement)
   ├─ ACCEPTED                  → 할 일 없음 (주문은 이미 접수 상태)
   ├─ FILL                      → ExecutionSettlementService.settle
   │                              계좌 잠금 → execution 저장 → trade_order 체결 수량 갱신 → 현금·보유·홀딩 반영 → Settlement 저장
   └─ CANCELED/EXPIRED/REJECTED → OrderHoldReleaseService.release
                                  hold_release 기록 → 계좌 잠금 → trade_order 취소 → 홀딩 해제

exchange.reports — 시세 워커 1 (OpenOrderReportConsumer, 리스너 id·그룹 market)
   ├─ ACCEPTED                              → 열린 주문 등록 → 그 종목 첫 등록이면 KIS 실시간 구독
   └─ FILL(남은 수량 0)/CANCELED/EXPIRED/REJECTED → 열린 주문 제거 → 주문도 보는 사람도 없으면 구독 해제
```

## 왜 단일 작성자인가

오더북을 바꾸는 쪽이 여럿이면 같은 주문을 두고 경합한다(체결 도중 취소, 같은 주문에 두 틱이 배분 등).
경합을 락으로 막는 대신 **바꾸는 쪽을 하나로 만든다.**

- 주문·체결·취소·만료가 같은 키(종목 코드)로 같은 파티션에 들어가므로, 한 종목의 명령은 한 줄로 도착 순서대로 처리된다.
  접수한 취소보다 먼저 도착한 체결이 주문을 채웠으면 취소는 아무것도 하지 않는다(거래소의 "Too late to cancel").
- 시간 우선은 거래소에 도착한 순서(`exchange_order.accepted_at`)다. 실제 거래소도 회원사가 보낸 순서가 아니라 도달한 순서를 따른다.
- 서로 다른 종목은 다른 파티션·다른 워커라 병렬로 처리된다. **인기 종목 하나의 처리량은 워커 하나가 정한다.**
- 파티션 수(24)는 키 → 파티션 매핑이다. 늘리면 같은 종목이 다른 파티션으로 가 순서가 깨지므로 처음에 정해 만든다(자동 생성 끔).

## 명령 (`MatchingCommand`)

| 종류 | 쓰는 필드 | 보내는 곳 |
|------|-----------|-----------|
| `ORDER` | `orderId`, `accountId`, `orderMethod`, `orderType`, `price`, `quantity` | 증권사 `OrderCommandOutbox.order` (아웃박스) |
| `FILL` | `eventId`, `orderMethod`, `price`, `quantity`, `eventTimestamp` (KIS 틱) | `KafkaFillCommandPublisher` |
| `CANCEL` | `orderId` | 증권사 `OrderCommandOutbox.cancel` (아웃박스) |
| `EXPIRE` | `orderId` | 거래소 `OrderCommandPublisher.expire` |

- 공통: `type`, `stockCode`(파티션 키), `enqueuedAt`(큐 대기·지연 측정 기준). 필드는 추가만 한다.
- FILL·EXPIRE 는 `MatchingCommandSender` 가 브로커 기록 확인(ack)까지 기다린다(`matching.queue.ack-timeout`, 기본 1초).
  확인을 받지 못하면 **다시 보내지 않는다** — 새 offset 이 붙어 워터마크로 거를 수 없는 중복이 된다.
- 워커는 `validate()` 로 종류별 필수 필드를 확인한다. 실패하면 재시도 없이 DLT 로 보낸다.
  ORDER 는 결과를 알릴 수 있을 만큼(주문·계좌·매수매도)만 보고, 가격·수량이 틀린 주문은 REJECTED 로 알려 증권사가 홀딩을 풀게 한다.

## 통보 (`ExecutionReport`, `contract`)

| 종류 | 채우는 필드 | 증권사가 하는 일 |
|------|-------------|------------------|
| `ACCEPTED` | 주문·계좌·종목·매수매도 | 없음 |
| `REJECTED` | 〃 + `canceledQuantity`(주문 수량) | 주문 취소 + 홀딩 해제. 사용자에게는 취소로 보인다(상태 값을 늘리면 프론트 협의가 필요) |
| `FILL` | `executionId`, `price`, `quantity`, `cumQuantity`, `leavesQuantity` | 정산 |
| `CANCELED`·`EXPIRED` | `canceledQuantity`(오더북에서 빠진 남은 수량) | 주문 취소 + 홀딩 해제 |

- 계좌 ID 가 키다. 한 계좌의 통보는 한 파티션에서 도착 순서대로 처리된다.
- 같은 주문의 통보는 같은 종목 워커가 차례로 커밋하고 아웃박스 id 순서로 나가므로, 증권사에 그 순서대로 도착한다.

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
- 한계: 같은 내용을 **보내는 쪽이 두 번 넣으면** offset 이 달라 막지 못한다. 아웃박스 릴레이는 최소 한 번이라 ORDER 가 두 번 올 수 있어
  주문 번호로 한 번만 넣는다. 취소·만료는 상태로도 멱등하다(이미 끝난 주문은 무시).
- 측정 회차 사이에 토픽을 다시 만들면 offset 이 0부터 시작하므로 워터마크도 비운다.

## 체결 (`LimitOrderExecutionService.fillOnce`)

- **후보 조회** (`OrderBookRepository.fetchMatchingEntries`, 네이티브 SQL, `exchange_order` 의 `status = 'OPEN'`):
  - 매수 틱 → 매도 주문 중 `price <= 체결가`, 낮은 가격 우선 / 매도 틱 → 매수 주문 중 `price >= 체결가`, 높은 가격 우선
  - 같은 가격이면 `accepted_at`, `order_id` 순(시간 우선). `matching.limit-order-fetch-size`(기본 100)건까지
  - 시장가 주문은 센티넬 가격(매수 999,999,999 / 매도 0.01)이라 항상 지정가보다 앞선다
- **배분** (`LimitOrderMatchPlanner`): 틱 수량을 우선순위대로 후보에 나눈다. 순수 로직이라 DB 를 보지 않는다.
- **반영**: 오더북 주문을 다시 읽어 남은 수량으로 배분을 한 번 더 조이고 `applyFill`, 체결 원장(`exchange_fill`) 저장.
  체결 번호는 여기서 붙고 통보로 건너가 증권사 `execution` 의 번호가 된다.
- 체결마다 FILL 통보(이 체결 뒤의 누적 체결 수량·남은 수량 포함)를 같은 트랜잭션에서 아웃박스에 적는다.

## 취소·만료 (`OrderCancelService`, 거래소)

`cancelOnce`·`expireOnce` 는 같은 처리(`endOnce`)를 쓰고 통보 종류만 다르다.

1. 워터마크 전진 (0행이면 `DUPLICATE`)
2. 오더북에 없거나 이미 끝난 주문이면 `NOT_CANCELLABLE` — 아무것도 바꾸지 않고 통보도 없다
3. `exchange_order` 를 닫는다
4. 같은 트랜잭션에서 `CANCELED`/`EXPIRED` 통보(주문, 계좌, 종목, 매수·매도, 남은 수량)를 아웃박스에 적는다

- **만료**: 시장가 매수 홀딩은 그날 상한가로 잡으므로(`OrderPricingService.calculateMarketHoldAmount`) 다음 매매일로 넘어가면
  "홀딩 ≥ 체결금액" 보장이 깨진다. 장 마감 후 거래소가 오더북의 미체결 시장가 주문을 만료시킨다. 지정가는 대상이 아니다.
  스케줄러는 명령만 넣고, 넣지 못한 주문은 다음 실행 때 다시 대상이 된다.

## 주문 접수와 홀딩 (`OrderService`, 증권사)

- **매수**: 현금을 빼지 않고 주문 금액만큼 홀딩(`Account.holdAmount` + `OrderHold`). 지정가는 지정가 × 수량, 시장가는 당일 상한가 × 수량(올림).
- **매도**: 보유 주식 수량을 홀딩(`AccountStock.holdQuantity`).
- 계좌는 `findByIdWithLock` 으로 잠근다. 연관(회원·대회)을 조인하지 않는다 — PostgreSQL 은 잠글 테이블을 명시하지 않은 행 잠금을
  FROM 절의 모든 테이블에 걸어, 조인하면 참가자 전원이 공유하는 대회 행까지 잠긴다.
- 같은 트랜잭션에서 ORDER 명령을 아웃박스에 적는다. 주문이 롤백되면 명령도 나가지 않는다.
- **취소 API는 접수만 한다.** 권한과 "이미 취소/체결"(증권사가 아는 상태)을 먼저 보고 400/403, 통과하면 CANCEL 명령을 아웃박스에 적고
  202(`OrderCancelAcceptedResponse`). 최종 판정은 거래소가 하고, 결과는 주문 상태로 확인한다.

## 정산과 홀딩 해제 (증권사)

- **정산** (`ExecutionSettlementService.settle`, FILL 통보): 계좌를 잠근 **뒤** 주문을 읽는다(먼저 읽으면 같은 주문의 다른 정산이
  커밋한 체결 수량을 덮어쓴다). 체결 내역(`execution`, 번호는 거래소 것)을 저장하고, 주문의 체결 수량·상태를 통보의 누적 수량으로 맞춘 뒤
  현금·보유·홀딩을 반영하고 `Settlement` 를 저장한다. 체결 내역과 정산이 한 트랜잭션이라 "체결은 있는데 정산이 없는" 상태가 없다.
- **완료 판정**: 통보의 남은 수량이 0 이면 그 주문의 마지막 체결이다. 통보는 순서대로 오므로 앞선 체결은 이미 정산돼 있고,
  남은 홀딩(지정가보다 싸게 체결된 차액 등)을 모두 풀고 미션 이벤트를 낸다.
- **홀딩 해제** (`OrderHoldReleaseService.release`, CANCELED·EXPIRED·REJECTED 통보):
  1. `hold_release` 에 주문 번호를 넣는다(`ON CONFLICT DO NOTHING`). 0행이면 이미 푼 주문이라 건너뛴다
  2. 계좌를 잠근 뒤 주문을 취소 상태로 바꾸고 홀딩 해제 — 매수는 남은 `OrderHold` 전부, 매도는 통보의 남은 수량만큼
- 중복 방어: 정산은 `settlement.execution_id` 유니크, 홀딩 해제는 `hold_release.order_id`.
- 정산이 재시도를 다 써 DLT 로 간 체결이 나중에 처리되면 순서가 뒤바뀐다. 이때도 정산은 실패하지 않는다 —
  홀딩이 이미 풀렸으면 남은 만큼만 뺀다(`deductHold`).

## 아웃박스 (`global/outbox`)

- 업무 트랜잭션이 보낼 메시지를 `outbox_message` 에 함께 적는다(`OutboxWriter`, JDBC 배치). 함께 커밋되거나 함께 롤백된다.
- `OutboxRelay`(전용 스레드, 스케줄링이 꺼져도 돈다)가 id 순서대로 읽어 보내고, 브로커가 받은 뒤 지운다.
  보내고 지우기 전에 죽으면 다시 보낸다(최소 한 번). PostgreSQL advisory lock 으로 인스턴스가 여럿이어도 한 곳만 보낸다.
  보낼 것이 없으면 `outbox.relay.poll-interval`(20ms) 쉬므로 그만큼 지연에 더해진다.
- 증권사의 ORDER·CANCEL 과 거래소의 통보가 같은 장치를 쓴다. 서비스를 나누면 각자 자기 DB 에 이 테이블을 둔다.

## 실패 처리 (`KafkaConfig`)

| 대상 | 일시 실패 | 처리할 수 없는 메시지 |
|------|-----------|------------------------|
| `matching.commands` | **무한 재시도** (200ms → 5s 지수 백오프). 건너뛰면 유실되고 종목 안의 순서가 깨진다. 그동안 파티션이 멈추므로 lag 으로 드러난다 | 역직렬화 실패·`InvalidMatchingCommandException` → `matching.commands.DLT` |
| `exchange.reports` | 10회 재시도 후 DLT. 계좌 하나 때문에 파티션이 멈추지 않게 한다. DLT 로 간 통보는 관리자가 보고 다시 보낸다(아래) | `InvalidExecutionReportException` → `exchange.reports.DLT` |

- 에러 핸들러 빈(`DefaultErrorHandler`)은 하나만 둔다. 둘이면 Boot 가 기본 팩토리에 어느 쪽도 붙이지 않는다. 정산은 전용 컨테이너 팩토리를 쓴다.
- 역직렬화 실패는 `ErrorHandlingDeserializer` 가 감싸 원래 바이트 그대로 DLT 에 남긴다.
- **DLT 재처리** (`DeadLetterService`, 관리자 API `/api/admin/dlt`): 실패 사유(헤더)·원래 토픽·키·내용을 보고, 원인을 고친 뒤
  미처리분을 원래 토픽에 같은 키·같은 바이트로 다시 보내거나(`replay`) 버린다(`skip`). 자동으로 다시 보내지 않는다 — 원인이 그대로면
  같은 실패만 반복된다. 받는 쪽이 모두 멱등이라 다시 보내도 안전하다. 어디까지 처리했는지는 그룹 `dlt-replay` 의 커밋된 offset 이다.

## 커넥션 예산

워커 스레드는 처리하는 동안 커넥션을 하나씩 쥔다. 스테이징 풀 60 = 체결 워커 24 + 정산 워커 8 + 미션·알림 워커 4 + 아웃박스 릴레이 1 + HTTP 23 이상.
워커 수를 바꾸면 풀도 함께 맞춘다.

## 지표

| 이름 | 뜻 |
|------|----|
| `matching.enqueue{result=ok\|fail}` | 체결 명령을 큐에 넣은 수 / 넣지 못한 수(유실) |
| `matching.fill.queue.wait` | 큐에 넣은 뒤 워커가 꺼낼 때까지 |
| `matching.fill.e2e` | 큐에 넣은 뒤 체결이 커밋될 때까지 |
| `matching.fill.duplicate` | 워터마크로 건너뛴 체결 명령 수(재전달) |
| `settlement.e2e` | 체결 커밋 → 정산 커밋 (아웃박스 릴레이 시간 포함) |
| `outbox.relay.published` / `outbox.relay.failures` | 아웃박스에서 보낸 수 / 전송 실패 횟수 |
| `outbox.pending.rows` / `outbox.pending.oldest.age` | 아웃박스에서 보내기를 기다리는 행 수 / 가장 오래 기다린 시간(초). 5초마다 센다(`outbox.relay.pending-sample-interval`). Kafka lag 에 안 보이는 릴레이 밀림 |

워커 처리 시간·횟수·실패는 `spring.kafka.listener`(리스너 id 가 `name` 태그), 전송 ack 는 `spring.kafka.template`,
lag 과 DLT 적재 수는 kafka-exporter 로 본다. 대시보드는 `monitoring/grafana/dashboards/mixed-load-dashboard.json`.

## 테스트

- `LimitOrderExecutionServiceTest` — 오더북 배분·우선순위·부분 체결·통보 수량 (단위)
- `LimitOrderFillCoordinatorConcurrencyTest` — 재전달 건너뜀, 주문 없던 틱의 재전달, 같은 위치를 두 워커가 동시에 처리(좀비)
- `ConsumerWatermarkWriterTest` — 앞으로만 전진, 파티션별, 롤백 시 미기록
- `OrderCancelQueryCharacterizationTest` / `MarketOrderExpiryTest` — 접수(400/403), 취소 명령의 아웃박스 기록, 워커 취소·만료의 통보, 통보로 하는 홀딩 해제와 중복 통보
- `ExecutionSettlementServiceTest` / `SettlementAfterHoldReleaseTest` — 통보로 하는 정산·주문 갱신, 부분 체결, 늦게 온 체결
- `AccountLedgerConcurrencyTest` — 주문 접수·워커·정산·홀딩 해제가 한 계좌를 동시에 고칠 때 장부
- `MatchingQueueFlowIntegrationTest` / `SingleStockCommandConcurrencyIntegrationTest` (Testcontainers Kafka) — 실제 큐로 입구 → 체결 → 통보 → 정산, 취소·만료, 재전달
- `MatchingCommandConsumerIntegrationTest` — 위치 기록, 독 메시지 DLT 후에도 파티션이 계속 흐름

## 관련 파일

- `contract/` — 통보(`ExecutionReport`)와 토픽(`ExchangeTopics`)
- `domain/matching/queue/` — 명령, 전송(`MatchingCommandSender`), 입구(`KafkaFillCommandPublisher`, `OrderCommandPublisher`), 워커(`MatchingCommandConsumer`), 통보 아웃박스(`ExecutionReportOutbox`), 지표
- `domain/matching/entity/` — 오더북(`ExchangeOrder`), 체결 원장(`ExchangeFill`), 워터마크
- `domain/matching/service/` — 주문 접수(`OrderAcceptService`), 체결(`LimitOrderExecutionService`, `LimitOrderMatchPlanner`), 취소·만료(`OrderCancelService`)
- `domain/matching/repository/OrderBookRepository.java` — 후보 조회
- `domain/matching/scheduler/MarketOrderExpiryScheduler.java` — 만료 명령 발행
- `domain/order/service/OrderService.java` — 주문 접수·홀딩·취소 접수, `OrderCommandOutbox` — 주문·취소 명령
- `domain/order/service/OrderHoldReleaseService.java` — 취소·만료·거부 통보로 홀딩 해제
- `domain/settlement/` — 정산 워커(`ExecutionReportConsumer`)와 정산
- `global/outbox/` — 아웃박스 기록·릴레이
- `global/config/KafkaConfig.java` — 실패 처리·DLT
- `global/dlt/DeadLetterService.java` — DLT 조회·재전송·건너뜀
- `common/src/main/resources/kafka.yml` — 프로듀서·컨슈머 설정
- `docker-compose.yml`(로컬) / `docker-compose.staging-kafka.yml`(스테이징) — 브로커와 토픽
