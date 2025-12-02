-- Add survey completion flag to member table
DO $$
BEGIN
    -- 컬럼이 존재하지 않으면 추가
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'member' AND column_name = 'survey_completed') THEN
        ALTER TABLE member ADD COLUMN survey_completed boolean NOT NULL DEFAULT false;
    ELSE
        -- 컬럼이 이미 존재하는 경우 (ddl-auto: update로 추가되었을 수 있음)
        -- NULL 값을 false로 업데이트
        UPDATE member SET survey_completed = false WHERE survey_completed IS NULL;
        
        -- NOT NULL 제약조건이 없는 경우 추가
        IF EXISTS (
            SELECT 1 
            FROM information_schema.columns 
            WHERE table_name = 'member' 
            AND column_name = 'survey_completed' 
            AND is_nullable = 'YES'
        ) THEN
            ALTER TABLE member ALTER COLUMN survey_completed SET NOT NULL;
            ALTER TABLE member ALTER COLUMN survey_completed SET DEFAULT false;
        END IF;
    END IF;
END $$;

