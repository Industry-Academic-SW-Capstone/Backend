-- Add tutorial completion flags to member table
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'member' AND column_name = 'main_tutorial_completed') THEN
        ALTER TABLE member ADD COLUMN main_tutorial_completed boolean NOT NULL DEFAULT false;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'member' AND column_name = 'securities_depth_tutorial_completed') THEN
        ALTER TABLE member ADD COLUMN securities_depth_tutorial_completed boolean NOT NULL DEFAULT false;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'member' AND column_name = 'stock_detail_tutorial_completed') THEN
        ALTER TABLE member ADD COLUMN stock_detail_tutorial_completed boolean NOT NULL DEFAULT false;
    END IF;
END $$;