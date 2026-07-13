# 매칭 엔진

stockIt의 핵심 트레이딩 로직인 Redis 기반 지정가 매칭 엔진의 아키텍처와 설계 결정을 설명합니다.
관련 코드는 `domain/matching`, `domain/order`, `domain/execution`, `domain/account` 패키지에 있습니다.

## 전체 흐름

```
[주문 접수] OrderService
   ├─ 현금(BUY)/주식(SELL) 홀딩 (OrderHold)
   ├─ DB 커밋 후(afterCommit) → Redis 오더북 등록 (sim:order:book:{code}:{method})
   └─ 웹소켓 구독 등록
              │
[체결 트리거] KIS 실시간 체결 수신 → KisWebSocketClient
   └─ LimitOrderFillEventMessage (Spring ApplicationEvent) 발행
        └─ LimitOrderEventPublisher @EventListener
             └─ Redis 이벤트 큐 RPUSH (sim:limit:event:{code})
                  └─ LimitOrderMatchingService.consumeNextEvent(code)
                       ├─ 종목별 분산 락 획득 (sim:limit:lock:{code})
                       ├─ 큐에서 이벤트 pop
                       └─ LimitOrderExecutionService.distributeEvent (@Transactional)
                            ├─ Account 비관적 락 조회
                            ├─ 오더북 매칭 대상 조회·우선순위 정렬
                            ├─ 체결(부분/전량) 및 계좌·홀딩 갱신
                            ├─ Execution 저장
                            ├─ 잔량은 다시 이벤트 큐로 (enqueueResidualEvent)
                            ├─ 트랜잭션 마지막에 Redis 오더북 갱신/삭제
                            └─ 체결 이벤트 발행 (알림/후속 처리)
```

## 왜 이벤트 큐인가

KIS 실시간 체결 수신과 매칭 처리 사이에 Redis 리스트 큐(`sim:limit:event:{code}`)를 둡니다.

- 웹소켓 수신 스레드 블로킹 최소화
- 매칭 처리 실패가 수신에 영향을 주지 않음
- 동시 이벤트도 큐에 순서대로 적재
- 락 경합 시에도 이벤트가 큐에 보존됨

## 왜 Redis Sorted Set인가

주식 매칭의 핵심 원칙은 **가격 우선 → 시간 우선**입니다. 오더북은 항상 이 순서로 정렬돼 있어야 합니다.

- **Score** = 주문 가격(가격 우선), **Value** = 주문 정보(+ 시간 정렬용 부가 데이터)
- Skip List 기반 자동 정렬 → `ORDER BY` 불필요, `ZRANGE` 조회 O(log N)
- 모든 연산이 인메모리에서 수행되어 고속 처리
- RDBMS 대비: 매 주문/체결마다 B-Tree 재정렬 비용·병목을 피함

## Redis 키 스키마

| 키 패턴 | 타입 | 용도 |
|---------|------|------|
| `sim:order:book:{stockCode}:{method}` | Sorted Set | 매수/매도 오더북 (가격·시간 우선순위) |
| `sim:order:data:{orderId}` | String | 개별 주문 데이터 |
| `sim:limit:event:{stockCode}` | List | 체결 이벤트 큐 (RPUSH / pop) |
| `sim:limit:lock:{stockCode}` | String | 종목별 매칭 분산 락 (UUID 토큰) |
| `sim:price:last:{stockCode}` | String | 최종 체결가 |

## 주문 접수와 홀딩 (`OrderService`)

- **매수(BUY)**: 계좌에서 돈을 빼지 않고 주문 금액만큼 **홀딩**(`increaseHoldAmount` + `OrderHold`).
  묶인 금액을 뺀 나머지로만 다른 주문이 가능. 시장가 매수는 `order.market.hold-buffer-rate`(기본 0.05) 버퍼를 더해 홀딩.
- **매도(SELL)**: 보유 주식 수량을 홀딩(`applySellHold`).
- 부분 체결/취소 시 남은 홀딩은 해제.

## 매칭 알고리즘 (`LimitOrderExecutionService.distributeEvent`)

`@Transactional` 안에서 실행됩니다.

- **매칭 조건** (가격/시간 우선):
  - 매수 체결 이벤트 → 매도 주문 조회 (가격 ≤ 체결가)
  - 매도 체결 이벤트 → 매수 주문 조회 (가격 ≥ 체결가)
  - 동일 가격 내 시간순 처리
- **조회**: `RedisOrderBookRepository.fetchMatchingEntries(code, takerMethod, priceLimit, maxOrders)`,
  한 번에 처리하는 수는 `fetchSize`로 제한. `sortByPriority`로 정렬.
- **체결 대상 상태**: `PENDING` / `PARTIALLY_FILLED`만 처리(`ELIGIBLE_STATUSES`).
- **자금/수량 검증**: 매수 체결 시 계좌 정산(`handleAccountOnFill`), 부족하면 가능 수량으로 축소
  (`calculateAffordableQuantity`)하거나 주문 취소(`cancelDueToInsufficientFunds`).
- **결과**: `Execution` 저장, 전량 체결 시 `finalizeFilledOrder`(Redis에서 제거), 부분 체결 시
  `remainingQuantity`만 업데이트하고 잔량을 `enqueueResidualEvent`로 재큐잉.

## 분산 락 (`LimitOrderMatchingService`)

종목 단위로 동시 매칭을 직렬화합니다. DB 락/Zookeeper 대신 Redis를 쓰는 이유는 **데이터가 있는 곳에서
락을 관리해 네트워크 오버헤드를 줄이고 아키텍처를 단순화**하기 위함입니다. (Lettuce, Netty 기반 논블로킹)

- **획득**: `setIfAbsent`(SETNX) + TTL 조합. 값은 고유 UUID 토큰.
  - 락 키: `sim:limit:lock:{stockCode}`, TTL: `matching.limit-lock-ttl-seconds`(기본 **5초**).
- **해제**: 내가 건 락이 맞는지 UUID 토큰을 비교한 뒤에만 삭제. 조회+삭제 원자성을 위해 Lua 스크립트 사용:

  ```lua
  if redis.call('get', KEYS[1]) == ARGV[1] then
      return redis.call('del', KEYS[1])
  else
      return 0
  end
  ```

- **종목별 독립 락** → 서로 다른 종목은 병렬 처리 가능.
- `consumeNextEvent`는 **락 획득/해제만** 담당(트랜잭션 없음), 실제 체결은 `distributeEvent`로 위임.

## 동시성 문제와 해결

### 1) 이중 체결 (분산 락)

> 스레드 A가 주문을 조회·체결하고 업데이트하기 전에, 스레드 B가 같은 주문을 조회하면
> 하나의 주문이 두 번 체결될 수 있음.

→ 종목별 Redis 분산 락으로 시간 윈도우를 제거.

### 2) 갱신 손실 (Account 비관적 락)

> 한 사용자가 삼성전자·SK하이닉스 매수를 동시에 시도하면, 두 스레드가 같은 잔고 기준으로 차감·업데이트해
> 갱신 손실(Lost Update)이 발생할 수 있음. (종목이 달라 분산 락은 서로 다른 락을 잡음)

→ 계좌는 DB 레벨 **비관적 락(PESSIMISTIC_WRITE)**으로 조회:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT a FROM Account a WHERE a.accountId = :accountId")
Optional<Account> findByIdWithLock(@Param("accountId") Long accountId);
```

비관적 락을 택한 이유: 충돌 빈도가 높은 환경(인기 종목), 낙관적 락의 재시도 낭비 회피,
체결 순서와 잔고 반영 순서 일치, 트랜잭션이 짧고 단순(조회→메모리 연산→업데이트).

## 이중 쓰기 정합성 (DB ↔ Redis)

Redis와 RDBMS는 하나의 트랜잭션으로 묶이지 않으므로 원자성이 보장되지 않습니다. 두 가지 패턴으로 대응합니다.

- **주문 생성 — AfterCommit 패턴**: DB 트랜잭션이 **완전히 커밋된 후에만** Redis 오더북을 갱신
  (`global/util/TransactionHandler.afterCommit(...)`). DB 커밋 실패 시 Redis도 미반영.
- **체결 처리 — 트랜잭션 마지막 Redis 갱신**: 비관적 락 조회 → 체결·DB 업데이트 → **트랜잭션 끝에서
  Redis 삭제**. Redis 실패 시 예외로 DB를 롤백해 중복 체결을 방지.
- 두 경우 모두 남는 불일치(Redis만 실패/삭제)는 아래 복구 배치가 정리.

## @Transactional 프록시 문제와 클래스 분리

같은 클래스 안에서 `@Transactional` 메서드를 내부 호출하면 프록시가 적용되지 않아 트랜잭션이 동작하지 않습니다.
그래서 책임을 두 클래스로 분리했습니다.

- `LimitOrderMatchingService` — 분산 락 획득/해제 (락 대기 중 DB 커넥션 점유 방지, 트랜잭션 없음)
- `LimitOrderExecutionService` — 체결 로직 (`@Transactional`)

→ AOP 프록시가 정상 동작하도록 보장.

## 장애 복구 배치 (`RedisDBSyncService`)

이중 쓰기로 생길 수 있는 DB↔Redis 불일치를 주기적으로 정리합니다.

- **주기**: `@Scheduled(fixedDelay = 60000)` — 1분마다
- **검사 범위**: 최근 `sync.recent-minutes`(기본 5분) 데이터만 (불일치는 생성 직후 발생하므로)
- **배치**: `sync.batch-size`(기본 100)건씩 페이징, 배치 간 `Thread.sleep(100)`
- **복구 항목**:
  - 주문 누락 — DB에 있는데 Redis에 없음 → Redis에 추가
  - 유령 주문 — Redis에만 있음 → 제거
- 커넥션 재사용 주의: `RedisTemplate` 내부에서 다시 Template을 호출하면 커넥션 풀 고갈로 데드락이
  생길 수 있어, 파라미터로 받은 connection 하나로 작업.

## 동시성 검증

`LimitOrderMatchingServiceConcurrencyTest`가 동시 주문 매칭 시 데이터 정합성(현금/보유수량/체결수량
합계 불변식)을 검증합니다.

## 향후 개선 계획

- 사용자 자산에 대한 동시 접근 문제(종목 간 동시성은 보장됨)를 추가 보완
- Redis List → Kafka/RabbitMQ (메시지 영속성)
- 폴링 복구 → 실시간 동기화
- Lettuce 스핀락 → Redisson(Pub/Sub) 검토

## 관련 파일

- `domain/matching/service/LimitOrderMatchingService.java` — 락·큐 소비
- `domain/matching/service/LimitOrderExecutionService.java` — 체결 트랜잭션
- `domain/matching/service/LimitOrderEventPublisher.java` — 이벤트 → 큐 적재
- `domain/matching/service/RedisDBSyncService.java` — 장애 복구 배치
- `domain/matching/repository/RedisOrderBookRepository.java` — 오더북 Redis 연산
- `domain/order/service/OrderService.java` — 주문 접수·홀딩
- `domain/account/repository/AccountRepository.java` — 비관적 락 조회
- `global/util/TransactionHandler.java` — afterCommit 헬퍼

> 참고: 이 문서는 코드 기준으로 작성되었습니다. 설계 배경 설명은
> [매칭엔진 블로그](https://hwanheee.tistory.com/20)를 함께 참고하세요.
