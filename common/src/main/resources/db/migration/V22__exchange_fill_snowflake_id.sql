-- 체결 번호를 파티션 Snowflake 로 붙이기 (거래소)
--   대상: 거래소 DB 를 파티션별로 나눠도 체결 번호가 샤드 사이에서 겹치지 않게 하기 위함
--
-- 체결 번호는 이제 거래소 워커가 붙인다(시각·파티션·순번). IDENTITY 를 떼고, 워터마크에 파티션별 마지막 번호를 남겨
-- 담당 워커가 바뀌어도 그 번호 뒤에서 이어 붙게 한다. 기존 행의 번호는 그대로 둔다.
-- 적용 조건: 새 코드를 배포하기 전에 적용한다(새 코드의 워터마크 갱신이 last_fill_id 를 RETURNING 하므로 열이 없으면 실패한다).
--   거래소 워커를 멈추고 적용한다. IDENTITY 는 남아 있어도 동작은 같다(새 코드가 번호를 직접 붙인다).
-- Flyway 자동 실행이 꺼져 있어 이 파일은 자동 적용되지 않는다(V10~V21 과 같다).

ALTER TABLE exchange.consumer_watermark ADD COLUMN IF NOT EXISTS last_fill_id BIGINT;
ALTER TABLE exchange.exchange_fill ALTER COLUMN fill_id DROP IDENTITY IF EXISTS;
