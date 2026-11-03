-- Add password column to contest table (nullable)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'contest' AND column_name = 'password') THEN
        ALTER TABLE contest ADD COLUMN password VARCHAR(255);
    END IF;
END $$;
