-- 벤치마크: 인덱스 복구
--
-- 실행 (ops 서버, 저장소 루트에서):
--   export PGPASSWORD=$(grep '^POSTGRES_PASSWORD' .env | cut -d= -f2-)
--   psql -h "$RDS_HOST" -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f benchmark/index-on.sql
--
-- 회차 초기화는 benchmark/reset.sh 가 함께 처리한다.

-- 정의는 마이그레이션과 동일해야 한다.
--   V10__add_orderbook_index.sql
--   V11__add_matching_event_queue.sql

-- 오더북 조회용 부분 인덱스.
-- 선두 두 컬럼이 등가 조건이므로 뒤의 (price, created_at)이 인덱스 안에서 이미 정렬돼 있다.
-- ORDER BY가 추가 sort를 유발하지 않고, B-tree 역방향 스캔으로 매수(DESC)·매도(ASC)를 함께 덮는다.
-- 부분 인덱스가 쓰이려면 쿼리에 동일한 술어(status IN ...)가 그대로 있어야 한다.
CREATE INDEX IF NOT EXISTS idx_orderbook_active
    ON trade_order (stock_code, order_method, price, created_at)
    WHERE status IN ('PENDING', 'PARTIALLY_FILLED');

-- 이벤트 큐 소비용. WHERE stock_code = ? ORDER BY seq_no, id LIMIT 1 을 덮는다.
CREATE INDEX IF NOT EXISTS idx_matching_event_queue
    ON matching_event_queue (stock_code, seq_no);

ANALYZE trade_order;
ANALYZE matching_event_queue;

-- 확인
SELECT tablename, indexname
FROM pg_indexes
WHERE tablename IN ('trade_order', 'matching_event_queue')
ORDER BY tablename, indexname;
