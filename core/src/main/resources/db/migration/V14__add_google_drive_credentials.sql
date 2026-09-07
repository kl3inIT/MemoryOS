ALTER TABLE connectors DROP CONSTRAINT ck_connectors_type;
ALTER TABLE connectors ADD CONSTRAINT ck_connectors_type CHECK (connector_type IN ('FILE', 'GOOGLE_DRIVE'));
ALTER TABLE credentials DROP CONSTRAINT ck_credentials_kind;
ALTER TABLE credentials ADD CONSTRAINT ck_credentials_kind CHECK (credential_kind IN ('NO_AUTH', 'GOOGLE_OAUTH'));
ALTER TABLE credentials DROP CONSTRAINT ck_credentials_status;
ALTER TABLE credentials ADD CONSTRAINT ck_credentials_status CHECK (
    (credential_kind = 'NO_AUTH' AND status = 'ACTIVE') OR
    (credential_kind = 'GOOGLE_OAUTH' AND status IN ('ACTIVE', 'NEEDS_REAUTHORIZATION', 'REVOKED'))
);
ALTER TABLE connector_credential_pairs DROP CONSTRAINT ck_pairs_access;
ALTER TABLE connector_credential_pairs ADD CONSTRAINT ck_pairs_access CHECK (access_type IN ('PUBLIC', 'RESTRICTED'));
CREATE UNIQUE INDEX uq_google_drive_connector_per_tenant ON connectors (tenant_id)
    WHERE connector_type = 'GOOGLE_DRIVE';

CREATE TABLE google_drive_credentials (
    tenant_id UUID NOT NULL,
    credential_id UUID NOT NULL,
    account_subject VARCHAR(255) NOT NULL,
    account_email VARCHAR(320) NOT NULL,
    granted_scopes TEXT NOT NULL,
    connection_status VARCHAR(32) NOT NULL,
    credential_revision BIGINT NOT NULL DEFAULT 1,
    payload_revision BIGINT NOT NULL DEFAULT 1,
    refresh_token_ciphertext BYTEA,
    refresh_token_nonce BYTEA,
    key_version VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, credential_id),
    CONSTRAINT fk_google_drive_credential FOREIGN KEY (tenant_id, credential_id)
        REFERENCES credentials (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_google_drive_revision CHECK (credential_revision > 0 AND payload_revision > 0),
    CONSTRAINT ck_google_drive_account CHECK (CHAR_LENGTH(account_subject) > 0 AND CHAR_LENGTH(account_email) > 0),
    CONSTRAINT ck_google_drive_connection CHECK (connection_status IN ('ACTIVE', 'NEEDS_REAUTHORIZATION', 'REVOKED')),
    CONSTRAINT ck_google_drive_envelope CHECK (
        (connection_status = 'REVOKED' AND refresh_token_ciphertext IS NULL AND refresh_token_nonce IS NULL AND key_version IS NULL)
        OR (connection_status IN ('ACTIVE', 'NEEDS_REAUTHORIZATION') AND refresh_token_ciphertext IS NOT NULL
            AND OCTET_LENGTH(refresh_token_ciphertext) BETWEEN 17 AND 16400
            AND refresh_token_nonce IS NOT NULL AND OCTET_LENGTH(refresh_token_nonce) = 12
            AND key_version IS NOT NULL AND CHAR_LENGTH(key_version) BETWEEN 1 AND 64)
    )
);
