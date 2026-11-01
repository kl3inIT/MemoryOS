ALTER TABLE connector_credential_pairs DROP CONSTRAINT ck_pairs_access;
UPDATE connector_credential_pairs SET access_type = 'PRIVATE' WHERE access_type = 'RESTRICTED';
ALTER TABLE connector_credential_pairs
    ADD CONSTRAINT ck_pairs_access CHECK (access_type IN ('PUBLIC', 'PRIVATE', 'SYNC'));

-- A pending Drive creation intent from before this migration has no access; it resolves to PRIVATE, as before.
ALTER TABLE google_drive_selection_operations
    ADD COLUMN access_type VARCHAR(16),
    ADD CONSTRAINT ck_google_selection_access CHECK (access_type IN ('PUBLIC', 'PRIVATE', 'SYNC'));
