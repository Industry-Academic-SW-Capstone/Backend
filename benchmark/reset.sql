-- 측정 회차 사이 상태 초기화.
--
-- 왜 필요한가: 회차마다 같은 시작 상태여야 반복 측정이 의미를 갖는다. 안 지우면 오더북이
-- 회차마다 커져서 후보 스캔 비용이 늘고, 그 차이가 엉뚱한 원인으로 귀속된다.
--
-- 지우지 않는 것: stock(종목 마스터), member, account, contest, mission 계열.
-- 회차별 상태가 아니라 환경 구성이다.

-- settlement 을 명시한다. 운영에는 execution 에 외래키가 있어 CASCADE 가 딸려 지우지만,
-- 스테이징은 ddl-auto 가 외래키를 만들지 않아 CASCADE 가 닿지 않는다. 빼먹으면 정산 행이
-- 회차 간에 누적되고, 회차 후 미정산 집계가 이전 회차 찌꺼기까지 센다.
TRUNCATE TABLE settlement, execution, order_hold, trade_order
    RESTART IDENTITY CASCADE;

-- 시딩 계정 복원. 매도 시딩은 account_stock 보유분을, 매수는 cash를 소모한다.
UPDATE account SET cash = 100000000000, hold_amount = 0;
UPDATE account_stock SET quantity = 1000000000, hold_quantity = 0;

-- 죽은 행 버전을 회수한다.
--
-- 왜 필요한가: 체결 1건마다 account 와 account_stock 을 갱신하는데, 부하 테스트는 계좌를
-- 하나만 쓴다. 한 회차에 같은 행을 수만 번 갱신하므로 죽은 버전이 그만큼 쌓인다.
-- PostgreSQL 의 UPDATE 는 새 버전을 추가하고 이전 버전을 표시만 하기 때문이다.
-- autovacuum 은 부하 중에 활성 트랜잭션보다 오래된 것만 회수할 수 있어 따라오지 못한다.
--
-- 정리하지 않으면 살아 있는 행은 2개인데 테이블이 수백 페이지로 불어나고, 체결마다 하는
-- 계좌 조회 비용이 회차를 거듭할수록 늘어난다. 실제로 연속 측정에서 단건 처리 시간이
-- 8ms 에서 15ms 로 늘어 arm 간 비교가 오염됐다.
--
-- TRUNCATE 한 테이블은 파일을 통째로 비우므로 대상이 아니다.
VACUUM (ANALYZE) account, account_stock;

SELECT 'trade_order' AS t, count(*) FROM trade_order
UNION ALL SELECT 'execution', count(*) FROM execution
UNION ALL SELECT 'order_hold', count(*) FROM order_hold
UNION ALL SELECT 'settlement', count(*) FROM settlement;

-- 회수됐는지 확인. n_dead_tup 이 0에 가까워야 한다.
SELECT relname, n_live_tup, n_dead_tup
FROM pg_stat_user_tables
WHERE relname IN ('account', 'account_stock')
ORDER BY relname;
