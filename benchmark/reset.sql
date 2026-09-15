-- 측정 회차 사이 상태 초기화.
--
-- 왜 필요한가: 회차마다 같은 시작 상태여야 반복 측정이 의미를 갖는다. 안 지우면 오더북이
-- 회차마다 커져서 후보 스캔 비용이 늘고, 그 차이가 엉뚱한 원인으로 귀속된다.
--
-- 지우지 않는 것: stock(종목 마스터), member, account, contest, mission 계열.
-- 회차별 상태가 아니라 환경 구성이다.

TRUNCATE TABLE execution, order_hold, trade_order
    RESTART IDENTITY CASCADE;

-- 시딩 계정 복원. 매도 시딩은 account_stock 보유분을, 매수는 cash를 소모한다.
UPDATE account SET cash = 100000000000, hold_amount = 0;
UPDATE account_stock SET quantity = 1000000000, hold_quantity = 0;

SELECT 'trade_order' AS t, count(*) FROM trade_order
UNION ALL SELECT 'execution', count(*) FROM execution
UNION ALL SELECT 'order_hold', count(*) FROM order_hold;
