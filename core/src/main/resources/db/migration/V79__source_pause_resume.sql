ALTER TABLE connector_credential_pairs DROP CONSTRAINT ck_pairs_status;
ALTER TABLE connector_credential_pairs ADD CONSTRAINT ck_pairs_status
    CHECK (status IN ('NOT_STARTED', 'INDEXING', 'ACTIVE', 'FAILED', 'PAUSED', 'DELETING'));

ALTER TABLE source_sync_attempts DROP CONSTRAINT IF EXISTS source_sync_attempts_trigger_kind_check;
ALTER TABLE source_sync_attempts DROP CONSTRAINT IF EXISTS ck_source_sync_attempts_trigger;
ALTER TABLE source_sync_attempts ADD CONSTRAINT ck_source_sync_attempts_trigger
    CHECK (trigger_kind IN ('SCHEDULED', 'MANUAL', 'INITIAL', 'RESUMED'));
