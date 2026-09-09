ALTER TABLE google_drive_sources
    ADD COLUMN sync_interval_minutes INTEGER NOT NULL DEFAULT 5 CHECK (sync_interval_minutes >= 1),
    ADD COLUMN schedule_revision BIGINT NOT NULL DEFAULT 1 CHECK (schedule_revision >= 1);
