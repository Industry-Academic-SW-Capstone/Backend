-- member 테이블의 NULL 값을 기본값으로 업데이트하는 스크립트
-- 실행 방법: psql -U choij -d database -f fix_member_columns.sql

DO $$
BEGIN
    -- notification_agreement 컬럼 처리
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'member' AND column_name = 'notification_agreement'
    ) THEN
        -- 컬럼이 있으면 NULL 값을 false로 업데이트
        UPDATE member 
        SET notification_agreement = false 
        WHERE notification_agreement IS NULL;
        
        -- NOT NULL 제약 조건 추가
        ALTER TABLE member ALTER COLUMN notification_agreement SET NOT NULL;
        ALTER TABLE member ALTER COLUMN notification_agreement SET DEFAULT false;
    ELSE
        -- 컬럼이 없으면 추가 (기본값 false)
        ALTER TABLE member ADD COLUMN notification_agreement BOOLEAN NOT NULL DEFAULT false;
    END IF;

    -- two_factor_enabled 컬럼 처리
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'member' AND column_name = 'two_factor_enabled'
    ) THEN
        -- 컬럼이 있으면 NULL 값을 false로 업데이트
        UPDATE member 
        SET two_factor_enabled = false 
        WHERE two_factor_enabled IS NULL;
        
        -- NOT NULL 제약 조건 추가
        ALTER TABLE member ALTER COLUMN two_factor_enabled SET NOT NULL;
        ALTER TABLE member ALTER COLUMN two_factor_enabled SET DEFAULT false;
    ELSE
        -- 컬럼이 없으면 추가 (기본값 false)
        ALTER TABLE member ADD COLUMN two_factor_enabled BOOLEAN NOT NULL DEFAULT false;
    END IF;
END $$;

