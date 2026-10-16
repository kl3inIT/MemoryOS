-- One synchronization engine serves Google Drive and SharePoint (phase 3). Its state that the Source summary
-- and run history read lives in provider-neutral tables.

-- The synchronization error the Source summary shows. connector_credential_pairs.error_code stays owned by
-- indexing reconciliation (recomputeStatus), so the sync error gets its own column.
ALTER TABLE connector_credential_pairs ADD COLUMN sync_error_code VARCHAR(64);

UPDATE connector_credential_pairs pair SET sync_error_code = s.error_code
FROM google_drive_sources s
WHERE s.tenant_id = pair.tenant_id AND s.source_id = pair.id AND s.error_code IS NOT NULL;

UPDATE connector_credential_pairs pair SET sync_error_code = s.error_code
FROM sharepoint_sources s
WHERE s.tenant_id = pair.tenant_id AND s.source_id = pair.id AND s.error_code IS NOT NULL;

ALTER TABLE google_drive_sources DROP COLUMN error_code;
ALTER TABLE sharepoint_sources DROP COLUMN error_code;

-- A run whose failures stayed isolated to single items completes with errors instead of failing. It used to
-- be derived at read time from FAILED plus progress; it is now written by the engine, and the rows that the
-- read projection showed as completed with errors keep that outcome.
ALTER TABLE source_sync_attempts DROP CONSTRAINT source_sync_attempts_status_check;
ALTER TABLE source_sync_attempts ADD CONSTRAINT source_sync_attempts_status_check
    CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'SUCCEEDED', 'COMPLETED_WITH_ERRORS', 'FAILED',
                      'SUPERSEDED', 'CANCELLED'));

UPDATE source_sync_attempts SET status = 'COMPLETED_WITH_ERRORS'
WHERE status = 'FAILED' AND (acquired > 0 OR unchanged > 0);

-- The abort threshold counts item failures against items processed since the attempt last started over;
-- these hold the counters as they stood at that point.
ALTER TABLE source_sync_attempts
    ADD COLUMN failure_window_scanned BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN failure_window_failed BIGINT NOT NULL DEFAULT 0;

-- An item error is resolved when a later run acquires that item, as Onyx resolves IndexAttemptError rows.
ALTER TABLE source_run_errors
    ADD COLUMN resolved_at TIMESTAMPTZ,
    ADD COLUMN resolved_by_run_id UUID;
CREATE INDEX ix_source_run_errors_unresolved ON source_run_errors (tenant_id, run_id)
    WHERE resolved_at IS NULL AND file_id IS NOT NULL;

CREATE OR REPLACE FUNCTION source_run_error_stage(code TEXT) RETURNS TEXT
LANGUAGE SQL IMMUTABLE AS $$
    SELECT CASE
        WHEN code LIKE 'SOURCE_STORAGE_READ_%' THEN 'STORAGE_READ'
        WHEN code LIKE 'SOURCE_STORAGE_WRITE_%' THEN 'STORAGE_WRITE'
        WHEN code LIKE 'SOURCE_EXTRACTION_%' THEN 'EXTRACTION'
        WHEN code LIKE 'SOURCE_PUBLICATION_%' THEN 'PUBLICATION'
        WHEN code LIKE 'SOURCE_GOOGLE_%' THEN 'PROVIDER'
        WHEN code LIKE 'SOURCE_SHAREPOINT_%' THEN 'PROVIDER'
        ELSE 'SYSTEM' END
$$;

-- A SharePoint run resolves its libraries and sites once, and lists the items an earlier run failed on;
-- continuations read them from here instead of asking Microsoft again every slice.
ALTER TABLE sharepoint_sync_runs ADD COLUMN scope_resolved BOOLEAN NOT NULL DEFAULT FALSE;
CREATE TABLE sharepoint_sync_run_scope (
    tenant_id UUID NOT NULL,
    run_id UUID NOT NULL,
    kind VARCHAR(8) NOT NULL CHECK (kind IN ('DRIVE', 'SITE', 'RETRY')),
    position INTEGER NOT NULL,
    drive_id VARCHAR(512),
    site_id VARCHAR(512),
    item_id VARCHAR(512),
    PRIMARY KEY (tenant_id, run_id, kind, position),
    FOREIGN KEY (tenant_id, run_id) REFERENCES sharepoint_sync_runs (tenant_id, id) ON DELETE CASCADE
);
