ALTER TABLE google_drive_credentials ADD COLUMN oauth_client_ciphertext BYTEA;
ALTER TABLE google_drive_credentials ADD COLUMN oauth_client_nonce BYTEA;
ALTER TABLE google_drive_credentials ADD COLUMN oauth_client_key_version VARCHAR(64);
ALTER TABLE google_drive_credentials DROP CONSTRAINT ck_google_drive_envelope;

-- Old grants were issued to the deployment app, not an owner-supplied app.
UPDATE google_drive_credentials
SET connection_status = CASE WHEN connection_status = 'REVOKED' THEN 'REVOKED' ELSE 'NEEDS_REAUTHORIZATION' END,
    refresh_token_ciphertext = NULL, refresh_token_nonce = NULL, key_version = NULL,
    credential_revision = credential_revision + 1, payload_revision = payload_revision + 1,
    updated_at = CURRENT_TIMESTAMP;

UPDATE credentials credential
SET status = google.connection_status, updated_at = CURRENT_TIMESTAMP
FROM google_drive_credentials google
WHERE credential.tenant_id = google.tenant_id AND credential.id = google.credential_id;

ALTER TABLE google_drive_credentials ADD CONSTRAINT ck_google_drive_oauth_client CHECK (
    (oauth_client_ciphertext IS NULL AND oauth_client_nonce IS NULL AND oauth_client_key_version IS NULL)
    OR (oauth_client_ciphertext IS NOT NULL AND OCTET_LENGTH(oauth_client_ciphertext) BETWEEN 17 AND 4516
        AND oauth_client_nonce IS NOT NULL AND OCTET_LENGTH(oauth_client_nonce) = 12
        AND oauth_client_key_version IS NOT NULL AND CHAR_LENGTH(oauth_client_key_version) BETWEEN 1 AND 64)
);
ALTER TABLE google_drive_credentials ADD CONSTRAINT ck_google_drive_envelope CHECK (
    (connection_status IN ('REVOKED', 'NEEDS_REAUTHORIZATION')
        AND refresh_token_ciphertext IS NULL AND refresh_token_nonce IS NULL AND key_version IS NULL)
    OR (connection_status IN ('ACTIVE', 'NEEDS_REAUTHORIZATION') AND oauth_client_ciphertext IS NOT NULL
        AND refresh_token_ciphertext IS NOT NULL AND OCTET_LENGTH(refresh_token_ciphertext) BETWEEN 17 AND 16400
        AND refresh_token_nonce IS NOT NULL AND OCTET_LENGTH(refresh_token_nonce) = 12
        AND key_version IS NOT NULL AND CHAR_LENGTH(key_version) BETWEEN 1 AND 64)
);

UPDATE source_sync_attempts SET status = 'CANCELLED', claim_token = NULL, lease_expires_at = NULL,
    delivery_id = NULL, dispatch_token = NULL, dispatch_lease_expires_at = NULL,
    error_code = 'GOOGLE_DRIVE_NEEDS_REAUTHORIZATION', completed_at = CURRENT_TIMESTAMP
WHERE status IN ('NOT_STARTED', 'IN_PROGRESS');

UPDATE index_attempts attempt SET status = 'CANCELLED', claim_token = NULL, lease_expires_at = NULL,
    delivery_id = NULL, dispatch_token = NULL, dispatch_lease_expires_at = NULL,
    completed_at = CURRENT_TIMESTAMP
FROM connector_credential_pairs pair, google_drive_credentials google
WHERE attempt.tenant_id = pair.tenant_id AND attempt.connector_credential_pair_id = pair.id
    AND pair.tenant_id = google.tenant_id AND pair.credential_id = google.credential_id
    AND attempt.status IN ('NOT_STARTED', 'IN_PROGRESS');

UPDATE documents_by_connector_credential_pair mapping SET retrieval_eligible = FALSE
FROM connector_credential_pairs pair, google_drive_credentials google
WHERE mapping.tenant_id = pair.tenant_id AND mapping.connector_credential_pair_id = pair.id
    AND pair.tenant_id = google.tenant_id AND pair.credential_id = google.credential_id;
UPDATE google_drive_membership SET eligible = FALSE;
UPDATE google_drive_sources SET error_code = 'GOOGLE_DRIVE_NEEDS_REAUTHORIZATION';
UPDATE connector_credential_pairs pair SET status = 'FAILED', error_code = 'GOOGLE_DRIVE_NEEDS_REAUTHORIZATION',
    updated_at = CURRENT_TIMESTAMP
FROM google_drive_credentials google
WHERE pair.tenant_id = google.tenant_id AND pair.credential_id = google.credential_id AND pair.status <> 'DELETING';

DELETE FROM spring_session_attributes
WHERE attribute_name = 'io.memoryos.api.source.GoogleDriveAuthorizationSessionState';
