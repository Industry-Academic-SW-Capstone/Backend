-- notification_type_check 제약조건에 MARKET_OPEN, MARKET_CLOSE_REMINDER 추가
ALTER TABLE notification DROP CONSTRAINT IF EXISTS notification_type_check;

ALTER TABLE notification ADD CONSTRAINT notification_type_check 
    CHECK (type IN (
        'EXECUTION',
        'MISSION_COMPLETED',
        'RANKING',
        'ACHIEVEMENT',
        'CONTEST',
        'SYSTEM',
        'MARKET_OPEN',
        'MARKET_CLOSE_REMINDER'
    ));

