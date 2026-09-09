-- =============================================
-- RDB 이벤트 큐 (Redis List 대체)
--   실험 브랜치: experiment/rdb-orderbook-benchmark
--   구현: JpaMatchingEventQueue
-- =============================================
--
-- 종목별 FIFO 큐다. seq_no가 순서를 정한다.
--   일반 유입(enqueue)      : 해당 종목 MAX(seq_no) + 1  → 뒤에 추가
--   잔여분 되돌리기(requeue) : 해당 종목 MIN(seq_no) - 1  → 앞에 추가
--
-- 잔여 수량은 다음 차례에 즉시 재처리되어야 하므로 일반 유입보다 먼저 소비되어야 한다.
-- Redis List의 LPUSH 의미를 seq_no로 옮긴 것이다.
--
-- 동시 유입으로 seq_no가 겹칠 수 있으나(서브쿼리 MAX 경합), 조회가
-- ORDER BY seq_no, id 이므로 순서는 결정적이다.
--
-- 소비는 SELECT … FOR UPDATE SKIP LOCKED + DELETE 2왕복이다. Redis의 LPOP 1왕복보다
-- 비싸고 INSERT/DELETE 반복으로 dead tuple이 쌓인다 — 이 비용을 재는 것이 벤치마크의
-- 목적 중 하나다(soak 시나리오에서 pg_stat_user_tables.n_dead_tup 관측).

CREATE TABLE IF NOT EXISTS matching_event_queue (
    id              BIGSERIAL     PRIMARY KEY,
    stock_code      VARCHAR(20)   NOT NULL,
    seq_no          BIGINT        NOT NULL,
    event_id        VARCHAR(64)   NOT NULL,
    order_method    VARCHAR(20)   NOT NULL,
    price           NUMERIC(19,2) NOT NULL,
    quantity        INTEGER       NOT NULL,
    event_timestamp BIGINT        NOT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT now()
);

-- 소비 쿼리(WHERE stock_code = ? ORDER BY seq_no, id LIMIT 1)를 그대로 덮는다.
CREATE INDEX IF NOT EXISTS idx_matching_event_queue
    ON matching_event_queue (stock_code, seq_no);
