-- 회차 직후 정합성 검증. reset 하기 전에 실행한다.
--
--   psql -h $H -U $U -d $D -f benchmark/verify-consistency.sql
--
-- 왜 필요한가: 체결과 정산을 다른 트랜잭션으로 나누면서 "체결은 커밋됐는데 계좌에 반영되지
-- 않은" 상태가 생길 수 있게 됐다. 미정산 건수만 보는 것으로는 부족하다 — 그건 "정산 행이
-- 있다"만 말하고 금액이 맞는지는 말하지 않는다.
--
-- 아래는 잔고를 정산 원장에서 재구성해 실제 값과 대조한다. settlement 에 증감(delta)을
-- 남긴 이유가 이것이고, 이 대조가 통과해야 "나눠도 틀리지 않았다"고 말할 수 있다.
--
-- 전제
--   1) 현금과 보유 수량은 정산에서만 바뀐다. 주문 접수는 홀딩만 건드린다.
--      따라서 "초기값 + 증감 합계 = 현재값"이 근사가 아니라 항등식이다.
--   2) 초기값은 reset.sql 이 고정한 상수다 (cash 1000억, quantity 10억).
--      회차 중에 새로 만들어진 계좌가 있으면 그 계좌는 이 상수를 따르지 않는다.
--      k6 는 setup 에서 기존 계정으로 로그인하므로 정상 회차에서는 해당 없다.
--
-- 전부 0 이어야 한다. 0 이 아닌 줄이 있으면 그 아래 상세 조회로 내려간다.

\echo '=== 정합성 검증 (전부 0 이어야 한다) ==='

WITH
-- ① 정산되지 않은 체결. 빠른 경로와 복구 배치가 모두 놓친 건이다.
unsettled AS (
    SELECT count(*) AS n
    FROM execution e
    WHERE NOT EXISTS (SELECT 1 FROM settlement s WHERE s.execution_id = e.execution_id)
),

-- ② 현금이 원장과 어긋난 계좌.
--    매수는 cash_delta 가 음수, 매도는 양수다.
cash_mismatch AS (
    SELECT count(*) AS n
    FROM account a
    LEFT JOIN (
        SELECT account_id, sum(cash_delta) AS delta
        FROM settlement GROUP BY account_id
    ) s ON s.account_id = a.account_id
    WHERE a.cash <> 100000000000 + COALESCE(s.delta, 0)
),

-- ③ 보유 수량이 원장과 어긋난 행.
--    settlement 은 종목을 갖지 않으므로(경계를 지키려고 식별자만 둔다) execution 을 거쳐 잇는다.
quantity_mismatch AS (
    SELECT count(*) AS n
    FROM account_stock ast
    LEFT JOIN (
        SELECT s.account_id, e.stock_code, sum(s.quantity_delta) AS delta
        FROM settlement s
        JOIN execution e ON e.execution_id = s.execution_id
        GROUP BY s.account_id, e.stock_code
    ) x ON x.account_id = ast.account_id AND x.stock_code = ast.stock_code
    WHERE ast.quantity <> 1000000000 + COALESCE(x.delta, 0)
),

-- ④ 매도 홀딩이 샌 행. 매도는 OrderHold 행이 아니라 account_stock.hold_quantity 로 묶는다.
--    주문 접수가 잔여 수량만큼 늘리고 정산이 체결 수량만큼 줄이므로,
--    살아 있는 매도 주문의 잔여 합과 같아야 한다.
sell_hold_mismatch AS (
    SELECT count(*) AS n
    FROM account_stock ast
    LEFT JOIN (
        SELECT account_id, stock_code, sum(quantity - filled_quantity) AS remaining
        FROM trade_order
        WHERE order_method = 'SELL' AND status IN ('PENDING', 'PARTIALLY_FILLED')
        GROUP BY account_id, stock_code
    ) o ON o.account_id = ast.account_id AND o.stock_code = ast.stock_code
    WHERE ast.hold_quantity <> COALESCE(o.remaining, 0)
),

-- ⑤ 매수 홀딩 금액이 샌 계좌. 홀딩 해제가 정산 트랜잭션으로 넘어가서 생긴 위험이다.
buy_hold_mismatch AS (
    SELECT count(*) AS n
    FROM account a
    LEFT JOIN (
        SELECT account_id, sum(hold_amount) AS held
        FROM order_hold WHERE hold_status = 'ACTIVE' GROUP BY account_id
    ) h ON h.account_id = a.account_id
    WHERE a.hold_amount <> COALESCE(h.held, 0)
),

-- ⑥ 전량 체결됐는데 홀딩이 아직 살아 있는 주문. 돈이 묶인 채 남는다.
--    ① 이 0 이어도 이것이 0 이 아닐 수 있다 — 정산은 됐는데 해제만 빠진 경우다.
stale_hold AS (
    SELECT count(*) AS n
    FROM order_hold h
    JOIN trade_order o ON o.order_id = h.order_id
    WHERE o.quantity <= o.filled_quantity AND h.hold_status = 'ACTIVE'
),

-- ⑦ 주문의 체결 수량이 체결 기록 합과 다른 주문. 트랜잭션 1 내부의 원자성을 본다.
filled_mismatch AS (
    SELECT count(*) AS n
    FROM trade_order o
    LEFT JOIN (
        SELECT order_id, sum(quantity) AS q FROM execution GROUP BY order_id
    ) e ON e.order_id = o.order_id
    WHERE o.filled_quantity <> COALESCE(e.q, 0)
)

SELECT '① 미정산 체결'                  AS 검사, n FROM unsettled
UNION ALL SELECT '② 현금 ≠ 원장',            n FROM cash_mismatch
UNION ALL SELECT '③ 보유수량 ≠ 원장',        n FROM quantity_mismatch
UNION ALL SELECT '④ 매도 홀딩 불일치',       n FROM sell_hold_mismatch
UNION ALL SELECT '⑤ 매수 홀딩 불일치',       n FROM buy_hold_mismatch
UNION ALL SELECT '⑥ 전량체결 후 홀딩 잔존',  n FROM stale_hold
UNION ALL SELECT '⑦ 체결수량 ≠ 체결기록 합', n FROM filled_mismatch;

\echo ''
\echo '=== 참고 수치 ==='

SELECT
    (SELECT count(*) FROM execution)                          AS 체결,
    (SELECT count(*) FROM settlement)                         AS 정산,
    (SELECT COALESCE(sum(cash_delta), 0) FROM settlement)     AS 원장_현금증감,
    (SELECT COALESCE(sum(quantity_delta), 0) FROM settlement) AS 원장_수량증감,
    (SELECT count(*) FROM trade_order
      WHERE status IN ('PENDING', 'PARTIALLY_FILLED'))        AS 잔여주문;

-- 정산 지연 분포. matching.settle 타이머와 별개로, 커밋 시각 차이로도 확인할 수 있다.
-- created_at 이 초 단위 이상 해상도일 때만 의미가 있으므로 참고용이다.
SELECT
    round(avg(EXTRACT(EPOCH FROM (s.created_at - e.created_at)) * 1000)::numeric, 2) AS 평균_ms,
    round(max(EXTRACT(EPOCH FROM (s.created_at - e.created_at)) * 1000)::numeric, 2) AS 최대_ms
FROM settlement s JOIN execution e ON e.execution_id = s.execution_id;
