ALTER TABLE actors ADD COLUMN ui_language varchar(2) NOT NULL DEFAULT 'vi';
ALTER TABLE actors ADD CONSTRAINT actors_ui_language_check CHECK (ui_language IN ('vi', 'en'));
