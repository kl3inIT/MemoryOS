ALTER TABLE google_drive_sources DROP COLUMN changes_token;

ALTER TABLE source_sync_attempts
    DROP COLUMN full_scan,
    DROP COLUMN page_token,
    DROP COLUMN final_token,
    DROP COLUMN restart_count,
    DROP CONSTRAINT source_sync_attempts_phase_check;

UPDATE source_sync_attempts SET phase = 'SCAN' WHERE phase IN ('CHANGES', 'FINISH');

ALTER TABLE source_sync_attempts ADD CONSTRAINT source_sync_attempts_phase_check
    CHECK (phase IN ('START', 'SCAN'));
