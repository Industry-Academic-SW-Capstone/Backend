-- 홀딩 해제 기록
--   대상: 거래소의 취소·만료 통보(exchange.reports)를 최소 한 번 전달로 받을 때 같은 주문의 홀딩을 두 번 풀지 않기 위함
--
-- 홀딩을 풀 때 같은 트랜잭션에서 주문 번호를 넣는다. 이미 있으면 0행이라 건너뛴다(정산의 execution_id 유니크와 같은 역할).
-- Flyway 자동 실행이 꺼져 있어 이 파일은 자동 적용되지 않는다(V10~V13 과 같다).

CREATE TABLE IF NOT EXISTS hold_release (
    order_id   BIGINT    PRIMARY KEY,
    created_at TIMESTAMP NOT NULL
);
