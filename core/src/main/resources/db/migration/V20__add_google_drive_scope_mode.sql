ALTER TABLE google_drive_sources
    ADD COLUMN scope_mode VARCHAR(16) NOT NULL DEFAULT 'SPECIFIC'
        CHECK (scope_mode IN ('GENERAL', 'SPECIFIC'));
