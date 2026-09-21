-- MEM-90: a domain-wide-delegated service account is a second way to hold Google Drive authority.
ALTER TABLE credentials DROP CONSTRAINT ck_credentials_kind;
ALTER TABLE credentials ADD CONSTRAINT ck_credentials_kind
    CHECK (credential_kind IN ('NO_AUTH', 'GOOGLE_OAUTH', 'GOOGLE_SERVICE_ACCOUNT', 'SHAREPOINT_APP'));
ALTER TABLE credentials DROP CONSTRAINT ck_credentials_status;
ALTER TABLE credentials ADD CONSTRAINT ck_credentials_status CHECK (
    (credential_kind = 'NO_AUTH' AND status = 'ACTIVE') OR
    (credential_kind IN ('GOOGLE_OAUTH', 'GOOGLE_SERVICE_ACCOUNT') AND status IN ('ACTIVE', 'NEEDS_REAUTHORIZATION', 'REVOKED')) OR
    (credential_kind = 'SHAREPOINT_APP' AND status IN ('ACTIVE', 'NEEDS_UPDATE'))
);
-- ck_credential_owner_kind is unchanged: a service account reaches the whole Workspace, so only global Source
-- managers hold one and it never has an owner.

ALTER TABLE google_drive_credentials ADD COLUMN auth_method VARCHAR(32) NOT NULL DEFAULT 'OAUTH';
ALTER TABLE google_drive_credentials ADD COLUMN service_account_email VARCHAR(320);
ALTER TABLE google_drive_credentials ADD COLUMN service_account_key_ciphertext BYTEA;
ALTER TABLE google_drive_credentials ADD COLUMN service_account_key_nonce BYTEA;
ALTER TABLE google_drive_credentials ADD COLUMN service_account_key_version VARCHAR(64);
ALTER TABLE google_drive_credentials ALTER COLUMN auth_method DROP DEFAULT;
ALTER TABLE google_drive_credentials ADD CONSTRAINT ck_google_drive_auth_method
    CHECK (auth_method IN ('OAUTH', 'SERVICE_ACCOUNT'));

ALTER TABLE google_drive_credentials DROP CONSTRAINT ck_google_drive_envelope;
ALTER TABLE google_drive_credentials ADD CONSTRAINT ck_google_drive_envelope CHECK (
    (auth_method = 'OAUTH' AND service_account_email IS NULL AND service_account_key_ciphertext IS NULL
        AND service_account_key_nonce IS NULL AND service_account_key_version IS NULL
        AND ((connection_status IN ('REVOKED', 'NEEDS_REAUTHORIZATION')
                AND refresh_token_ciphertext IS NULL AND refresh_token_nonce IS NULL AND key_version IS NULL)
            OR (connection_status IN ('ACTIVE', 'NEEDS_REAUTHORIZATION') AND oauth_client_ciphertext IS NOT NULL
                AND refresh_token_ciphertext IS NOT NULL AND OCTET_LENGTH(refresh_token_ciphertext) BETWEEN 17 AND 16400
                AND refresh_token_nonce IS NOT NULL AND OCTET_LENGTH(refresh_token_nonce) = 12
                AND key_version IS NOT NULL AND CHAR_LENGTH(key_version) BETWEEN 1 AND 64)))
    OR (auth_method = 'SERVICE_ACCOUNT' AND CHAR_LENGTH(service_account_email) > 0
        AND refresh_token_ciphertext IS NULL AND refresh_token_nonce IS NULL AND key_version IS NULL
        AND oauth_client_ciphertext IS NULL AND oauth_client_nonce IS NULL AND oauth_client_key_version IS NULL
        AND ((connection_status = 'REVOKED' AND service_account_key_ciphertext IS NULL
                AND service_account_key_nonce IS NULL AND service_account_key_version IS NULL)
            OR (connection_status IN ('ACTIVE', 'NEEDS_REAUTHORIZATION')
                AND OCTET_LENGTH(service_account_key_ciphertext) BETWEEN 17 AND 16400
                AND OCTET_LENGTH(service_account_key_nonce) = 12
                AND CHAR_LENGTH(service_account_key_version) BETWEEN 1 AND 64)))
);
