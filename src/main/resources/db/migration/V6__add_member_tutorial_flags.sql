-- Add tutorial completion flags to member table
ALTER TABLE member
ADD COLUMN main_tutorial_completed boolean NOT NULL DEFAULT false,
ADD COLUMN securities_depth_tutorial_completed boolean NOT NULL DEFAULT false,
ADD COLUMN stock_detail_tutorial_completed boolean NOT NULL DEFAULT false;