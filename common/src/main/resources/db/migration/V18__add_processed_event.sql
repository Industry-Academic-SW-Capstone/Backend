-- 받은 이벤트 처리 기록
--   대상: member.events 를 최소 한 번 전달로 받을 때 미션·알림이 같은 이벤트를 두 번 처리하지 않기 위함
--
-- 소비자(mission, notification)마다 처리한 이벤트 ID 를 처리와 같은 트랜잭션에서 넣는다. 이미 있으면 0행이라 건너뛴다.
-- Flyway 자동 실행이 꺼져 있어 이 파일은 자동 적용되지 않는다(V10~V17 과 같다).

CREATE TABLE IF NOT EXISTS processed_event (
    consumer     VARCHAR(50) NOT NULL,
    event_id     VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP   NOT NULL,
    PRIMARY KEY (consumer, event_id)
);
