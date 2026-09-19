-- 정산 기록
--   대상: 체결과 정산을 다른 트랜잭션으로 가를 때의 멱등성과 복구 지점
--
-- 체결(trade_order 갱신 + execution 기록)과 정산(현금·보유종목·홀딩 반영)을 나누면
-- "체결됐지만 아직 정산되지 않은" 상태가 생긴다. 그 체결을 찾아 마저 처리하는 것이
-- 복구 배치이고, 이 테이블이 "이미 정산했다"는 사실의 기록이다.
--
-- 설계 근거
--   1) execution_id 가 유니크다. 같은 체결을 두 번 정산하는 것이 DB 수준에서 불가능해진다.
--      정산 반영과 이 행의 INSERT 가 한 트랜잭션이므로, 커밋됐으면 기록이 있고 롤백됐으면 없다.
--      execution 에 플래그 컬럼을 두는 방식은 읽기와 쓰기 사이가 열려 있어 조회 쿼리를
--      정확히 써야만(SKIP LOCKED·시간 가드) 막히는데, 유니크 제약은 코드와 무관하게 막는다.
--   2) 체결 도메인의 엔티티를 참조하지 않고 식별자만 둔다. 정산은 체결 이벤트 없이
--      복구 배치로도 시작되므로 매칭·체결에 의존하지 않는 편이 경계가 분명하다.
--      다만 참조 무결성은 DB 에 맡긴다 — execution_id 에 외래키를 건다. 경계는 코드에서
--      유지하고(연관 매핑 없이 Long), 없는 체결을 가리키는 행은 DB 가 거부한다.
--   3) 증감(delta)을 남긴다. "정산했다"만이 아니라 "얼마를 움직였는지"가 남아야 감사와
--      재구성이 된다. 나중에 이 행을 잔액의 원천으로 승격하면 계좌 행 갱신이 사라진다.
--
-- Flyway 자동 실행이 꺼져 있어 이 파일은 자동 적용되지 않는다(V10 과 같다).
-- 테이블과 유니크 제약은 ddl-auto: update 가 만들어 주지만 외래키는 만들지 않는다
-- (연관 매핑이 아니라 Long 컬럼이므로). 즉 운영에만 외래키가 있는 비대칭이 남는다.
-- 그래도 거는 편이 낫다 — 없으면 잘못된 참조가 조용히 저장되고, 있으면 적어도 운영에서 막힌다.

CREATE TABLE IF NOT EXISTS settlement (
    settlement_id  BIGSERIAL PRIMARY KEY,
    execution_id   BIGINT NOT NULL REFERENCES execution(execution_id),
    account_id     BIGINT NOT NULL,
    cash_delta     NUMERIC(19,2) NOT NULL,
    quantity_delta INTEGER NOT NULL,
    created_at     TIMESTAMP NOT NULL,
    updated_at     TIMESTAMP,
    deleted_at     TIMESTAMP,
    CONSTRAINT uk_settlement_execution_id UNIQUE (execution_id)
);
