-- Add password column to contest table (nullable)
ALTER TABLE contest
ADD COLUMN password VARCHAR(255);
