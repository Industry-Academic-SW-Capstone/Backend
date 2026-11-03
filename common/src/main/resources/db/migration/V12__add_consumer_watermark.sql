-- 큐 소비 위치 기록
--   대상: matching.commands 를 최소 한 번 전달로 받을 때 같은 명령을 두 번 체결하지 않기 위함
--
-- 파티션마다 "어디까지 반영했나"(offset) 한 행을 둔다. 체결과 같은 트랜잭션에서
-- last_offset < 새 offset 일 때만 올리므로, 재전달된 명령은 갱신 0행으로 걸러진다.
-- 행 잠금이 같은 파티션을 동시에 처리하는 두 워커(리밸런싱 직후)도 한 줄로 세운다.
--
-- 토픽을 다시 만들면 offset 이 0 부터 다시 시작한다. 그때는 이 테이블도 비워야 한다.
-- Flyway 자동 실행이 꺼져 있어 이 파일은 자동 적용되지 않는다(V10·V11 과 같다).

CREATE TABLE IF NOT EXISTS consumer_watermark (
    topic        VARCHAR(200) NOT NULL,
    partition_no INTEGER      NOT NULL,
    last_offset  BIGINT       NOT NULL,
    updated_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (topic, partition_no)
);
