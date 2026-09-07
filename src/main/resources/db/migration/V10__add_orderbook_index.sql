-- =============================================
-- RDB 오더북 벤치마크용 인덱스
--   실험 브랜치: experiment/rdb-orderbook-benchmark
--   대상 쿼리: JpaOrderBookStore#fetchMatchingEntries
-- =============================================
--
-- 매칭 후보 조회는 항상 아래 형태다.
--
--   WHERE stock_code = ? AND order_method = ?
--     AND status IN ('PENDING','PARTIALLY_FILLED')
--     AND price <= ?              -- 매도 후보 (반대쪽은 price >= ?)
--   ORDER BY price ASC, created_at ASC   -- 매수 후보는 price DESC
--   LIMIT ?
--
-- 설계 근거
--   1) 선두 두 컬럼(stock_code, order_method)이 등가 조건이므로, 그 뒤의
--      (price, created_at)이 인덱스 안에서 이미 정렬된 상태다. 따라서 ORDER BY가
--      추가 sort를 유발하지 않고 인덱스 순서대로 스캔한다.
--   2) B-tree는 역방향 스캔이 가능하므로 매수(DESC)·매도(ASC) 양쪽을 인덱스 하나로 덮는다.
--   3) 오더북에 올라오는 주문은 미체결 주문뿐이므로 부분 인덱스로 만든다. 체결·취소된
--      주문이 인덱스에서 빠져 크기가 작게 유지되고, 상태 컬럼을 키에 넣지 않아도 된다.
--      부분 인덱스가 쓰이려면 쿼리에 동일한 술어가 그대로 있어야 한다
--      (JpaOrderBookStore.ACTIVE_STATUS_PREDICATE와 반드시 일치시킬 것).
--
-- 주의: 이 인덱스가 없으면 RDB 오더북은 Seq Scan + 정렬로 동작한다. 그 상태의 측정치는
-- "RDB vs Redis"가 아니라 "인덱스 없음 vs 있음"이므로 벤치마크 근거가 되지 못한다.

CREATE INDEX IF NOT EXISTS idx_orderbook_active
    ON trade_order (stock_code, order_method, price, created_at)
    WHERE status IN ('PENDING', 'PARTIALLY_FILLED');
