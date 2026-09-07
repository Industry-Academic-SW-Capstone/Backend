# 오더북 백엔드 벤치마크 (Redis vs RDB)

브랜치: `experiment/rdb-orderbook-benchmark`

## 왜 하는가

Redis 오더북을 도입한 근거가 측정으로 뒷받침되지 않았다. 기존 논거는 "RDBMS는 매 체결마다
정렬 쿼리가 필요하지만 Redis ZSet은 Skip List로 자동 정렬된다"였는데, 이 비교는 **인덱스 없는
RDB를 전제**한다. 복합 인덱스를 걸면 B-tree도 O(log N)이고 인덱스 순서대로 스캔하므로
ORDER BY가 추가 정렬을 유발하지 않는다. 실제로 `trade_order`에는 인덱스가 하나도 없었다.

이 실험의 목적은 Redis가 이기는 것도 지는 것도 아니고, **어느 쪽이 나은지 같은 조건에서
재는 것**이다. 결과가 어느 쪽이든 설계 근거가 생긴다.

## 가설

종목별 매칭은 가격 우선·시간 우선 원칙상 직렬화가 불가피하다(`MatchingLock` 주석 참고).
따라서 **임계 구역 길이가 곧 종목당 TPS 상한**이다.

```
종목당 TPS ≤ 1 / 락 보유 시간
```

오더북 조회·갱신은 임계 구역 안에서 일어나므로, 이 연산이 인메모리(Redis)냐 DB 왕복이냐가
TPS에 직접 곱해진다. 여기에 HikariCP 풀(기본 10)이 더해지면, RDB 백엔드는 임계 구역 안에서
커넥션을 더 오래 잡아 부하 상승 시 풀 고갈이 먼저 온다 — 이것이 Redis가 이길 것으로 보는 근거다.

반대로 RDB 백엔드는 **구조가 단순해진다**. 이중 쓰기가 없으므로 `RedisDBSyncService` 복구
배치가 무동작이 되고, advisory 락은 트랜잭션 종료 시 자동 해제되어 TTL 튜닝·해제 실패·락 해제와
커밋 사이의 중복 처리 창이 사라진다.

**따라서 비교 기준은 "처리량"이 아니라 "구조 단순화 대비 처리량 손실"이다.**

## 무엇을 바꿨나

매칭 정산 로직(`LimitOrderExecutionService`)은 **양쪽이 공유**한다. 오더북 자료구조와 락만
교체 가능하게 분리해 단일 변수 실험을 만들었다.

| 포트 | Redis 구현 | RDB 구현 |
|---|---|---|
| `OrderBookStore` | `RedisOrderBookRepository` (ZSet + Hash) | `JpaOrderBookStore` (`trade_order` + 부분 인덱스) |
| `MatchingLock` | `RedisMatchingLock` (SETNX + Lua 해제, TTL 5초) | `AdvisoryMatchingLock` (`pg_try_advisory_xact_lock`) |

전환은 프로퍼티 하나다.

```bash
MATCHING_ORDERBOOK_BACKEND=redis   # 기본
MATCHING_ORDERBOOK_BACKEND=jpa
```

이벤트 큐는 **두 백엔드 모두 Redis를 쓴다.** 오더북만 바꿔 비교하기 위한 통제이며, 큐 연산은
양쪽 모두 LPOP 1회로 동일해 비교를 편향시키지 않는다. 완전한 Redis 제거는 후속 단계다.

### RDB 백엔드의 핵심 — 오더북이 곧 주문 테이블

`JpaOrderBookStore`의 `addOrder`/`removeOrder`/`updateRemainingQuantity`는 **의도적 no-op**이다.
주문 행의 `status`와 `filled_quantity`가 곧 오더북 상태이므로, 정산 트랜잭션의 DB 변경으로 이미
끝나 있다. 이 때문에 유령 주문·주문 누락이 원리적으로 발생하지 않는다.

인덱스는 `V10__add_orderbook_index.sql`:

```sql
CREATE INDEX idx_orderbook_active
    ON trade_order (stock_code, order_method, price, created_at)
    WHERE status IN ('PENDING', 'PARTIALLY_FILLED');
```

선두 두 컬럼이 등가 조건이라 뒤의 `(price, created_at)`이 이미 정렬돼 있고, B-tree 역방향 스캔으로
매수(DESC)·매도(ASC)를 인덱스 하나로 덮는다. 부분 인덱스라 미체결 주문만 담아 크기가 작게 유지된다.

## 공정성 조건 (반드시 지킬 것)

1. **인덱스 없이 재지 않는다.** 인덱스가 빠지면 결과는 "RDB vs Redis"가 아니라
   "인덱스 없음 vs 있음"이 되고 아무것도 증명하지 못한다.
2. **벤치마크 환경에서 인덱스가 실제로 쓰이는지 EXPLAIN으로 확인한다.** 데이터가 적으면
   PostgreSQL이 Seq Scan을 고르는 게 정상이므로, 오더북에 충분한 주문을 쌓은 뒤 확인한다.

   ```sql
   EXPLAIN (ANALYZE, BUFFERS)
   SELECT o.order_id, o.price, (o.quantity - o.filled_quantity), o.created_at
   FROM trade_order o
   WHERE o.stock_code = '005930' AND o.order_method = 'BUY'
     AND o.status IN ('PENDING','PARTIALLY_FILLED')
     AND o.quantity > o.filled_quantity AND o.price >= 70000
   ORDER BY o.price DESC, o.created_at ASC LIMIT 100;
   -- 기대: Index Scan using idx_orderbook_active (Seq Scan이면 데이터가 부족한 것)
   ```
3. **정산 로직의 N+1은 양쪽 동일하게 둔다.** 현재 체결 주문 1건당 DB 약 5왕복이 발생하는데,
   이걸 한쪽만 고치면 오더북 차이가 가려진다. N+1 개선은 별도 실험이다.
4. **데이터 규모를 prod에 준하게 심고 기록한다.** 데이터가 적으면 전부 `shared_buffers`에 올라가
   RDB가 부당하게 유리해진다.
5. **동일 환경·동일 시나리오·3회 이상 반복**, 편차를 함께 기록한다.

## 측정 시나리오

| 시나리오 | 내용 | 측정 의미 |
|---|---|---|
| A. 단일 종목 | 한 종목에 부하 집중 | 직렬 구간의 상한. 임계 구역 길이 차이가 가장 선명 |
| B. 다종목 분산 | 수백 종목에 분산 | 실운영 프로파일. 종목 간 병렬로 확보되는 전체 처리량 |

락 키에 종목코드가 들어가므로 종목 간에는 이미 병렬이다. 단일 종목 TPS는 최악의 경우일 뿐
운영 프로파일이 아니다.

## 함께 기록할 지표

TPS만 보면 안 된다. 특히 아래 두 번째 항목은 **측정 자체의 신뢰도**와 직결된다.

- 락 보유 시간 (p50/p95/p99) — 현재 커스텀 Micrometer 메트릭이 없으므로 `Timer` 추가 필요
- **이벤트 큐 잔여 길이** (`LLEN sim:limit:event:*`) 와 **실제 체결 건수**
  — `consumeNextEvent`는 락 획득에 실패하면 이벤트를 큐에 남긴 채 빈 리스트를 반환하고,
  호출자는 200 OK를 받는다. 즉 **TPS는 높은데 체결은 안 되는 상태가 측정에 잡히지 않는다.**
- HikariCP 활성/대기 커넥션 수 (Actuator에 이미 있음, 노출만 켜면 됨)
- 호스트 CPU (`node-exporter`) — 부하 생성기가 병목이 아니었다는 근거

## 측정 환경

`docker-compose.staging.yml`을 쓴다. prod와 동일한 PostgreSQL 설정(`shared_buffers=256MB`,
`work_mem=4MB`, memory 1G)에 Traefik만 빠져 있어 부하 측정용으로 구성돼 있다.
`docker-compose.prod.yml`에는 k6가 없다 — 프로덕션에서는 돌리지 않는다.

k6는 backend와 같은 호스트에 있으므로, 별도 인스턴스로 분리하거나 최소한 node-exporter로
호스트 CPU 여유를 함께 기록해 생성기가 병목이 아니었음을 보여야 한다.

**어느 환경에서 쟀고 prod와 무엇이 다른지 결과에 그대로 적는다.** 숫자의 크기보다 측정의
타당성을 방어할 수 있느냐가 평가 기준이다.

## 알려진 제약

- 테스트 환경은 Flyway가 꺼져 있어(`spring.flyway.enabled=false`) V10 인덱스가 자동 적용되지
  않는다. `JpaOrderBookStoreIntegrationTest`는 마이그레이션 파일을 `@Sql`로 직접 적용한다.
- `AdvisoryMatchingLock`은 PostgreSQL 전용이다. H2에서는 동작하지 않으므로 `backend=jpa`는
  PostgreSQL 환경에서만 쓴다.
- RDB 백엔드에서 `RedisDBSyncService`는 무동작이 된다(`getAllOrderIdsByStock`이 빈 맵,
  `exists`가 항상 참). 의도된 결과이며, 이중 쓰기가 없어 복구할 대상이 없다는 뜻이다.

## 결과 기록

측정 후 아래 표를 채운다. **측정 전에 예상치를 먼저 적는다** — 재고 나서 설명하면 예측이
아니라 사후 합리화다.

| 시나리오 | 백엔드 | 예상 TPS | 실측 TPS | 락 보유 p95 | 큐 잔여 | 커넥션 풀 |
|---|---|---|---|---|---|---|
| A 단일종목 | redis | | | | | |
| A 단일종목 | jpa | | | | | |
| B 다종목 | redis | | | | | |
| B 다종목 | jpa | | | | | |
