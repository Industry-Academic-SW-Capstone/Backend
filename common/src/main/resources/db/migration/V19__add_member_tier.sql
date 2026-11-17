-- 회원 티어 복사본
--   대상: 랭킹(증권사)이 미션 점수(부가)를 직접 읽지 않고, 복사해 둔 티어로 보여 주기 위함
--
-- 이후 값은 부가 서비스가 extra.events 로 알린 티어로 바뀐다. 기존 회원은 지금 점수로 한 번 채운다.
-- 점수 = 활동 점수 + floor(sqrt(누적 수익금 / 10))(수익금이 0 이하면 0). 구간은 MissionTierPolicy 의 표와 같아야 한다.
-- Flyway 자동 실행이 꺼져 있어 이 파일은 자동 적용되지 않는다(V10~V18 과 같다).

ALTER TABLE member ADD COLUMN IF NOT EXISTS tier VARCHAR(20) DEFAULT 'BRONZE 1';

WITH score AS (
    SELECT mp.member_id,
           SUM(CASE WHEN m.condition_type = 'ACTIVITY_SCORE' THEN mp.current_value ELSE 0 END)
         + SUM(CASE WHEN m.condition_type = 'SKILL_SCORE' AND mp.current_value > 0
                    THEN FLOOR(SQRT(mp.current_value / 10.0))::INT ELSE 0 END) AS total
    FROM mission_progress mp
    JOIN mission m ON m.mission_id = mp.mission_id
    WHERE m.track = 'ACHIEVEMENT'
      AND m.condition_type IN ('ACTIVITY_SCORE', 'SKILL_SCORE')
    GROUP BY mp.member_id
)
UPDATE member mb
SET tier = CASE
        WHEN s.total >= 3600 THEN 'LEGEND'
        WHEN s.total >= 3400 THEN 'GRANDMASTER 3'
        WHEN s.total >= 3200 THEN 'GRANDMASTER 2'
        WHEN s.total >= 3000 THEN 'GRANDMASTER 1'
        WHEN s.total >= 2800 THEN 'MASTER 3'
        WHEN s.total >= 2600 THEN 'MASTER 2'
        WHEN s.total >= 2400 THEN 'MASTER 1'
        WHEN s.total >= 2200 THEN 'GOLD 3'
        WHEN s.total >= 2000 THEN 'GOLD 2'
        WHEN s.total >= 1800 THEN 'GOLD 1'
        WHEN s.total >= 1600 THEN 'SILVER 3'
        WHEN s.total >= 1400 THEN 'SILVER 2'
        WHEN s.total >= 1200 THEN 'SILVER 1'
        WHEN s.total >= 1000 THEN 'BRONZE 3'
        WHEN s.total >= 800 THEN 'BRONZE 2'
        ELSE 'BRONZE 1'
    END
FROM score s
WHERE mb.member_id = s.member_id;
