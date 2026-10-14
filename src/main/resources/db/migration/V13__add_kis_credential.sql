-- KIS 접근 토큰·웹소켓 승인키 저장
--   대상: 재배포 때마다 새로 발급받지 않도록 유효기간 동안 보관한다(KIS 는 발급 빈도를 제한한다).
-- Flyway 자동 실행이 꺼져 있어 이 파일은 자동 적용되지 않는다(V10~V12 와 같다).

CREATE TABLE IF NOT EXISTS kis_credential (
    credential_type  VARCHAR(20) PRIMARY KEY,
    credential_value TEXT        NOT NULL,
    expires_at       TIMESTAMP   NOT NULL
);
