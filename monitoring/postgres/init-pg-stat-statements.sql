-- pg_stat_statements 확장 활성화 (슬로우 쿼리 모니터링)
-- 이 스크립트는 DB 최초 초기화 시에만 실행됩니다.
-- 기존 DB에 적용하려면 psql에서 직접 실행하세요:
--   CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
