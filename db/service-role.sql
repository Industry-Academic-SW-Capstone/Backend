-- 서비스 DB 계정 — 서비스마다 자기 DB 인스턴스에 한 번 실행한다(관리자 계정, RDS 마스터).
-- 비밀번호는 파일에 적지 않고 실행할 때 넘긴다. service 는 exchange · broker · extra 중 하나(시세는 DB 가 없다).
--
--   psql "$ADMIN_URL" -v ON_ERROR_STOP=1 -v service=broker -v password='…' -f db/service-role.sql
--
-- 계정 stockit_<service> 를 만들고, 같은 이름의 schema 를 그 계정 소유로 만들고, 기본 search_path 를 그 schema 로 둔다.
-- 이미 그 schema 에 표가 있으면(옛 DB 를 V20 으로 나눈 경우) 표 소유권도 넘긴다 — ddl-auto 와 이후 마이그레이션을
-- 그 계정으로 돌릴 수 있게. RDS 마스터는 슈퍼유저가 아니라 소유권을 넘기려면 그 역할의 구성원이어야 해서 역할을 부여한다.
-- 다시 실행하면 CREATE ROLE 에서 멈춘다(이미 있음). 비밀번호만 바꾸려면 ALTER ROLE … PASSWORD 를 쓴다.

\set role 'stockit_' :service

CREATE ROLE :"role" LOGIN PASSWORD :'password';
GRANT :"role" TO CURRENT_USER;

CREATE SCHEMA IF NOT EXISTS :"service";
ALTER SCHEMA :"service" OWNER TO :"role";
ALTER ROLE :"role" SET search_path = :"service";
REVOKE ALL ON SCHEMA public FROM :"role";

SELECT format('ALTER TABLE %I.%I OWNER TO %I', schemaname, tablename, :'role')
FROM pg_tables
WHERE schemaname = :'service'
\gexec
