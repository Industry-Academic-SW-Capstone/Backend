\set ON_ERROR_STOP on

\echo '== 오더북 시딩 =='

WITH accounts AS (
    SELECT a.account_id,
           (row_number() OVER (ORDER BY a.account_id) - 1) AS idx
    FROM account a
    JOIN member m ON m.member_id = a.member_id
    WHERE a.is_default = true
      AND m.email LIKE 'loadtest%@stockit.local'
),
meta AS (
    SELECT count(*)::int AS n FROM accounts
)
INSERT INTO trade_order (
    account_id, stock_code, price, quantity, filled_quantity,
    order_type, order_method, status, created_at, updated_at
)
SELECT acc.account_id,
       :stock_code,
       (:base_price + (i % :price_levels))::numeric(19,2),
       1,
       0,
       'LIMIT',
       'SELL',
       'PENDING',
       now() + (i * interval '1 microsecond'),
       now()
FROM generate_series(0, :order_count - 1) AS i
CROSS JOIN meta
JOIN accounts acc ON acc.idx = (i / :price_levels) % meta.n;

-- 매도 홀딩을 주문과 맞춘다. verify-consistency.sql ④ 가 이 항등식을 검사한다:
--   account_stock.hold_quantity = 살아 있는 매도 주문의 잔여 합
-- reset.sql 이 hold_quantity 를 0 으로 되돌려 놓으므로 시딩 뒤에 다시 세운다.
\echo '== 매도 홀딩 정렬 =='

UPDATE account_stock ast
SET hold_quantity = o.remaining,
    updated_at = now()
FROM (
    SELECT account_id, stock_code, sum(quantity - filled_quantity)::int AS remaining
    FROM trade_order
    WHERE order_method = 'SELL'
      AND status IN ('PENDING', 'PARTIALLY_FILLED')
    GROUP BY account_id, stock_code
) o
WHERE ast.account_id = o.account_id
  AND ast.stock_code = o.stock_code;

-- TRUNCATE 직후에는 통계가 0행이라 옵티마이저가 부분 인덱스를 무시한다.
-- 시딩 뒤 ANALYZE 를 빼먹으면 Seq Scan 을 골라 회차가 통째로 무효가 된다.
\echo '== ANALYZE =='
ANALYZE trade_order;
ANALYZE account_stock;

\echo '== 확인 =='

-- 종목별 주문 수와 호가 범위
SELECT stock_code,
       count(*)                  AS 주문수,
       min(price)                AS 최저가,
       max(price)                AS 최고가,
       sum(quantity - filled_quantity) AS 잔여주식
FROM trade_order
WHERE order_method = 'SELL' AND status IN ('PENDING', 'PARTIALLY_FILLED')
GROUP BY stock_code
ORDER BY stock_code;

-- ★ 계좌가 실제로 흩어졌는지. 최저가 호가의 앞 10건이 서로 다른 계좌여야 한다.
\echo '-- 최저가 호가 앞 10건의 계좌 (전부 달라야 한다)'
SELECT order_id, account_id, price
FROM trade_order
WHERE stock_code = :stock_code
  AND order_method = 'SELL'
  AND status IN ('PENDING', 'PARTIALLY_FILLED')
ORDER BY price ASC, created_at ASC, order_id ASC
LIMIT 10;

-- 홀딩 항등식이 맞는지 (0 이어야 한다)
\echo '-- 매도 홀딩 불일치 건수 (0 이어야 한다)'
SELECT count(*) AS 불일치
FROM account_stock ast
LEFT JOIN (
    SELECT account_id, stock_code, sum(quantity - filled_quantity)::int AS remaining
    FROM trade_order
    WHERE order_method = 'SELL' AND status IN ('PENDING', 'PARTIALLY_FILLED')
    GROUP BY account_id, stock_code
) o ON o.account_id = ast.account_id AND o.stock_code = ast.stock_code
WHERE ast.hold_quantity <> COALESCE(o.remaining, 0);
