-- 서비스별 schema 로 나누기
--   대상: 시세·거래소·증권사·부가가 각자 자기 테이블만 보게 하기 위함(같은 database 안에서 schema·계정 분리)
--
-- 테이블은 ALTER TABLE ... SET SCHEMA 로 옮긴다. 복사가 아니라 소속만 바뀌므로 즉시 끝나고 데이터·인덱스·식별자 시퀀스가 그대로 따라간다.
-- 아웃박스와 처리 기록은 서비스마다 하나씩 둔다. 엔티티가 공통 모듈에 있어 표를 쓰지 않는 서비스(시세 등)에도 빈 표를 만든다(validate 용).
-- 서비스를 넘는 외래키는 지운다. 서비스 사이는 메시지와 조회 API 로만 잇고, 회원 번호 같은 값은 복사본이다.
--
-- 적용 조건: 모든 인스턴스를 멈추고 아웃박스가 비어 있어야 한다(아래에서 확인하고, 남아 있으면 멈춘다).
-- 적용 순서: V20 → db/service-roles.sql(서비스별 계정·소유권) → 서비스별 배포.
-- 적용 뒤 app 하나로 띄우면 DB_SEARCH_PATH=broker,exchange,extra 가 필요하다(테이블이 public 에 없다).
-- Flyway 자동 실행이 꺼져 있어 이 파일은 자동 적용되지 않는다(V10~V19 와 같다).

DO $$
BEGIN
    IF to_regclass('public.outbox_message') IS NOT NULL AND EXISTS (SELECT 1 FROM public.outbox_message) THEN
        RAISE EXCEPTION '아웃박스에 보내지 않은 메시지가 남아 있습니다. 릴레이가 비운 뒤 다시 실행하세요.';
    END IF;
END $$;

CREATE SCHEMA IF NOT EXISTS market;
CREATE SCHEMA IF NOT EXISTS exchange;
CREATE SCHEMA IF NOT EXISTS broker;
CREATE SCHEMA IF NOT EXISTS extra;

-- 1. 서비스 테이블 옮기기. 없는 테이블은 건너뛴다(환경마다 생성 이력이 다르다).
DO $$
DECLARE
    moves text[][] := ARRAY[
        ['exchange', 'exchange_order'],
        ['exchange', 'exchange_fill'],
        ['exchange', 'consumer_watermark'],
        ['broker', 'member'],
        ['broker', 'kakao_token'],
        ['broker', 'favorite_stock'],
        ['broker', 'account'],
        ['broker', 'account_stock'],
        ['broker', 'contest'],
        ['broker', 'stock'],
        ['broker', 'industry'],
        ['broker', 'trade_order'],
        ['broker', 'order_hold'],
        ['broker', 'hold_release'],
        ['broker', 'execution'],
        ['broker', 'settlement'],
        ['extra', 'mission'],
        ['extra', 'mission_progress'],
        ['extra', 'reward'],
        ['extra', 'title'],
        ['extra', 'member_title'],
        ['extra', 'notification'],
        ['extra', 'notification_setting']
    ];
    move text[];
BEGIN
    FOREACH move SLICE 1 IN ARRAY moves LOOP
        IF to_regclass(format('public.%I', move[2])) IS NOT NULL THEN
            EXECUTE format('ALTER TABLE public.%I SET SCHEMA %I', move[2], move[1]);
        END IF;
    END LOOP;
END $$;

-- 2. 아웃박스: 지금 표를 증권사로 옮기고(비어 있음) 나머지 서비스에 같은 모양의 빈 표를 만든다.
--    LIKE ... INCLUDING ALL 은 식별자(IDENTITY)도 표마다 새로 만든다.
DO $$
BEGIN
    IF to_regclass('public.outbox_message') IS NOT NULL THEN
        ALTER TABLE public.outbox_message SET SCHEMA broker;
    END IF;
END $$;
CREATE TABLE IF NOT EXISTS exchange.outbox_message (LIKE broker.outbox_message INCLUDING ALL);
CREATE TABLE IF NOT EXISTS extra.outbox_message (LIKE broker.outbox_message INCLUDING ALL);
CREATE TABLE IF NOT EXISTS market.outbox_message (LIKE broker.outbox_message INCLUDING ALL);

-- 3. 처리 기록: 소비자별로 나눈다. mission·notification 은 부가, broker 는 증권사.
DO $$
BEGIN
    IF to_regclass('public.processed_event') IS NOT NULL THEN
        ALTER TABLE public.processed_event SET SCHEMA extra;
    END IF;
END $$;
CREATE TABLE IF NOT EXISTS broker.processed_event (LIKE extra.processed_event INCLUDING ALL);
INSERT INTO broker.processed_event SELECT * FROM extra.processed_event WHERE consumer = 'broker'
ON CONFLICT DO NOTHING;
DELETE FROM extra.processed_event WHERE consumer = 'broker';
CREATE TABLE IF NOT EXISTS exchange.processed_event (LIKE extra.processed_event INCLUDING ALL);
CREATE TABLE IF NOT EXISTS market.processed_event (LIKE extra.processed_event INCLUDING ALL);

-- 4. 서비스를 넘는 외래키 지우기 (예: 대표 칭호 → 칭호, 회원 칭호·미션 진행도 → 회원).
DO $$
DECLARE
    fk record;
BEGIN
    FOR fk IN
        SELECT c.conname, n.nspname AS schema_name, t.relname AS table_name
        FROM pg_constraint c
                 JOIN pg_class t ON t.oid = c.conrelid
                 JOIN pg_namespace n ON n.oid = t.relnamespace
                 JOIN pg_class rt ON rt.oid = c.confrelid
                 JOIN pg_namespace rn ON rn.oid = rt.relnamespace
        WHERE c.contype = 'f'
          AND n.nspname IN ('market', 'exchange', 'broker', 'extra')
          AND n.nspname <> rn.nspname
    LOOP
        EXECUTE format('ALTER TABLE %I.%I DROP CONSTRAINT %I', fk.schema_name, fk.table_name, fk.conname);
        RAISE NOTICE '서비스를 넘는 외래키 삭제: %.% %', fk.schema_name, fk.table_name, fk.conname;
    END LOOP;
END $$;

-- 5. 회원에 남아 있던 알림 설정 컬럼 지우기. 6-b 에서 부가의 notification_setting 으로 옮겼다.
ALTER TABLE broker.member DROP COLUMN IF EXISTS fcm_token;
ALTER TABLE broker.member DROP COLUMN IF EXISTS execution_notification_enabled;
