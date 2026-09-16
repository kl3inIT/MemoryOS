-- New Google Drive Sources synchronize every 30 minutes; existing Sources keep their saved interval.
ALTER TABLE google_drive_sources ALTER COLUMN sync_interval_minutes SET DEFAULT 30;
