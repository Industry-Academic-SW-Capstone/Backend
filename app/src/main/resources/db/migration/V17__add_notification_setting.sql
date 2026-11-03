-- 알림 수신 설정
--   대상: FCM 토큰과 체결 알림 수신 여부를 회원 테이블(증권사)에서 알림(부가) 쪽 테이블로 옮기기 위함
--
-- 행이 없는 회원은 토큰 없음·체결 알림 켬으로 본다. 그래서 토큰이 있거나 체결 알림을 끈 회원만 옮긴다.
-- member.fcm_token, member.execution_notification_enabled 는 이 배포에서는 남겨 두고(되돌릴 수 있게) DB 를 나눌 때 지운다.
-- Flyway 자동 실행이 꺼져 있어 이 파일은 자동 적용되지 않는다(V10~V16 과 같다).

CREATE TABLE IF NOT EXISTS notification_setting (
    member_id                      BIGINT        PRIMARY KEY,
    fcm_token                      VARCHAR(1000),
    execution_notification_enabled BOOLEAN       NOT NULL DEFAULT TRUE
);

INSERT INTO notification_setting (member_id, fcm_token, execution_notification_enabled)
SELECT member_id, fcm_token, execution_notification_enabled
FROM member
WHERE fcm_token IS NOT NULL OR execution_notification_enabled = FALSE
ON CONFLICT (member_id) DO NOTHING;

-- 엔티티가 더 이상 쓰지 않는 NOT NULL 컬럼이라, 기본값이 없으면 회원 가입 INSERT 가 실패한다.
ALTER TABLE member ALTER COLUMN execution_notification_enabled SET DEFAULT TRUE;
