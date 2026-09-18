-- 측정용: 오더북 인덱스 제거
--
--   psql -h "$RDS_HOST" -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f benchmark/index-off.sql
--
-- 인덱스 없는 상태가 기준선이다. 이 상태의 수치는 "RDB 의 한계"가 아니라
-- "인덱스 없는 RDB 의 한계"로만 서술해야 한다.

DROP INDEX IF EXISTS idx_orderbook_active;

ANALYZE trade_order;

-- 아래 목록에 idx_orderbook_active 가 없어야 한다.
SELECT indexname FROM pg_indexes WHERE tablename = 'trade_order' ORDER BY indexname;
