-- 오더북 조회 인덱스
--   대상: OrderBookRepository#fetchMatchingEntries
--
-- 매칭 후보 조회는 항상 아래 형태다.
--
--   WHERE stock_code = ? AND order_method = ?
--     AND status IN ('PENDING','PARTIALLY_FILLED')
--     AND price <= ?                      -- 매도 후보 (반대쪽은 price >= ?)
--   ORDER BY price ASC, created_at ASC    -- 매수 후보는 price DESC
--   LIMIT ?
--
-- 설계 근거
--   1) 선두 두 컬럼(stock_code, order_method)이 등가 조건이므로 그 뒤의 (price, created_at)이
--      인덱스 안에서 이미 정렬돼 있다. ORDER BY가 추가 정렬을 유발하지 않는다.
--   2) B-tree 는 역방향 스캔이 가능해 매수(DESC)·매도(ASC) 양쪽을 인덱스 하나로 덮는다.
--   3) 오더북에 올라오는 것은 미체결 주문뿐이라 부분 인덱스로 만든다. 체결·취소된 주문이
--      인덱스에서 빠져 크기가 작게 유지되고, 상태 컬럼을 키에 넣지 않아도 된다.
--      부분 인덱스가 쓰이려면 쿼리에 동일한 술어가 그대로 있어야 한다
--      (OrderBookRepository.ACTIVE_STATUS_PREDICATE 와 반드시 일치시킬 것).
--
-- 없을 때의 비용: 체결 1건마다 trade_order 전체를 훑고 정렬한다. 즉 조회 비용이 테이블 전체
-- 행 수에 비례하므로, 부하가 늘지 않아도 주문이 쌓이는 것만으로 처리량이 떨어진다.

CREATE INDEX IF NOT EXISTS idx_orderbook_active
    ON trade_order (stock_code, order_method, price, created_at)
    WHERE status IN ('PENDING', 'PARTIALLY_FILLED');
