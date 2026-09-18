-- 측정용: 오더북 인덱스 생성
--
--   psql -h "$RDS_HOST" -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f benchmark/index-on.sql
--
-- 정의는 V10__add_orderbook_index.sql 과 동일해야 한다. 다르면 측정 결과가 운영과 무관해진다.
--
-- Flyway 자동 실행이 꺼져 있어 마이그레이션이 적용되지 않으므로, 측정 환경에서는 이 파일로
-- 직접 만든다. 이 토글 덕분에 같은 앱으로 인덱스 유무만 바꿔 비교할 수 있다.

CREATE INDEX IF NOT EXISTS idx_orderbook_active
    ON trade_order (stock_code, order_method, price, created_at)
    WHERE status IN ('PENDING', 'PARTIALLY_FILLED');

-- 통계를 갱신해야 옵티마이저가 인덱스를 선택한다. 빈 테이블 통계가 남아 있으면
-- 시딩 후에도 Seq Scan 을 고른다. 시딩이 끝난 뒤 한 번 더 돌릴 것.
ANALYZE trade_order;

SELECT indexname FROM pg_indexes WHERE tablename = 'trade_order' ORDER BY indexname;
