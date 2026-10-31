-- 대표 칭호 이름 복사
--   대상: 회원(증권사)이 칭호(부가)를 JPA 연관으로 읽지 않고, 복사해 둔 이름으로 프로필·랭킹을 보여 주기 위함
--
-- representative_title_id 는 그대로 두고 이름을 함께 둔다. 칭호를 장착할 때 둘을 함께 바꾼다.
-- Flyway 자동 실행이 꺼져 있어 이 파일은 자동 적용되지 않는다(V10~V15 와 같다).

ALTER TABLE member ADD COLUMN IF NOT EXISTS representative_title_name VARCHAR(100);

UPDATE member m
SET representative_title_name = t.name
FROM title t
WHERE m.representative_title_id = t.title_id
  AND m.representative_title_name IS NULL;
