-- 서비스별 DB 계정
--   V20(서비스별 schema) 다음에 관리자 계정(RDS 마스터)으로 한 번 실행한다. 비밀번호는 파일에 적지 않고 실행할 때 넘긴다.
--
--   psql "$ADMIN_URL" -v ON_ERROR_STOP=1 \
--     -v market_password='…' -v exchange_password='…' -v broker_password='…' -v extra_password='…' \
--     -f db/service-roles.sql
--
-- 계정마다 자기 schema 만 쓸 수 있다(다른 schema 권한 없음). 기본 search_path 를 자기 schema 로 두고, 자기 schema 의 표와
-- schema 자체를 소유한다 — ddl-auto(update/validate)와 이후 마이그레이션을 그 계정으로 돌릴 수 있게.
-- RDS 마스터는 슈퍼유저가 아니라 소유권을 넘기려면 그 역할의 구성원이어야 해서 마스터에게 역할을 부여한다.
-- 다시 실행하면 CREATE ROLE 에서 멈춘다(이미 있음). 비밀번호만 바꾸려면 ALTER ROLE … PASSWORD 를 쓴다.

CREATE ROLE stockit_market LOGIN PASSWORD :'market_password';
CREATE ROLE stockit_exchange LOGIN PASSWORD :'exchange_password';
CREATE ROLE stockit_broker LOGIN PASSWORD :'broker_password';
CREATE ROLE stockit_extra LOGIN PASSWORD :'extra_password';

GRANT stockit_market, stockit_exchange, stockit_broker, stockit_extra TO CURRENT_USER;

DO $$
DECLARE
    pairs text[][] := ARRAY[
        ['market', 'stockit_market'],
        ['exchange', 'stockit_exchange'],
        ['broker', 'stockit_broker'],
        ['extra', 'stockit_extra']
    ];
    pair text[];
    object record;
BEGIN
    FOREACH pair SLICE 1 IN ARRAY pairs LOOP
        EXECUTE format('ALTER SCHEMA %I OWNER TO %I', pair[1], pair[2]);
        EXECUTE format('ALTER ROLE %I SET search_path = %I', pair[2], pair[1]);
        EXECUTE format('REVOKE ALL ON SCHEMA public FROM %I', pair[2]);
        FOR object IN SELECT tablename FROM pg_tables WHERE schemaname = pair[1] LOOP
            EXECUTE format('ALTER TABLE %I.%I OWNER TO %I', pair[1], object.tablename, pair[2]);
        END LOOP;
    END LOOP;
END $$;
