-- MEM-245: a Lark Suite custom app (App ID and App Secret) is a credential kind of its own, held like a SharePoint
-- application: usable or asking for its secret to be replaced, and owned by the manager who added it.
ALTER TABLE credentials DROP CONSTRAINT ck_credentials_kind;
ALTER TABLE credentials ADD CONSTRAINT ck_credentials_kind
    CHECK (credential_kind IN ('NO_AUTH', 'GOOGLE_OAUTH', 'GOOGLE_SERVICE_ACCOUNT', 'SHAREPOINT_APP', 'LARK_APP'));
ALTER TABLE credentials DROP CONSTRAINT ck_credentials_status;
ALTER TABLE credentials ADD CONSTRAINT ck_credentials_status CHECK (
    (credential_kind = 'NO_AUTH' AND status = 'ACTIVE') OR
    (credential_kind IN ('GOOGLE_OAUTH', 'GOOGLE_SERVICE_ACCOUNT') AND status IN ('ACTIVE', 'NEEDS_REAUTHORIZATION', 'REVOKED')) OR
    (credential_kind IN ('SHAREPOINT_APP', 'LARK_APP') AND status IN ('ACTIVE', 'NEEDS_UPDATE'))
);
ALTER TABLE credentials DROP CONSTRAINT ck_credential_owner_kind;
ALTER TABLE credentials ADD CONSTRAINT ck_credential_owner_kind
    CHECK (owner_actor_id IS NULL OR credential_kind IN ('GOOGLE_OAUTH', 'SHAREPOINT_APP', 'LARK_APP'));

CREATE TABLE lark_credentials (
    tenant_id UUID NOT NULL,
    credential_id UUID NOT NULL,
    -- Lark international or Feishu: an app exists on one of them, and its address follows from this.
    region VARCHAR(16) NOT NULL,
    app_id VARCHAR(64) NOT NULL,
    secret_ciphertext BYTEA NOT NULL,
    secret_nonce BYTEA NOT NULL,
    secret_key_version VARCHAR(64) NOT NULL,
    -- The bot identity Lark returned at the last successful verification: what people add to a space or a folder.
    bot_name VARCHAR(255),
    bot_open_id VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, credential_id),
    CONSTRAINT fk_lark_credential FOREIGN KEY (tenant_id, credential_id)
        REFERENCES credentials (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_lark_region CHECK (region IN ('LARK', 'FEISHU')),
    CONSTRAINT ck_lark_app_id CHECK (app_id ~ '^cli_[0-9a-z]{8,40}$'),
    CONSTRAINT ck_lark_secret_envelope CHECK (
        OCTET_LENGTH(secret_ciphertext) BETWEEN 17 AND 1040
        AND OCTET_LENGTH(secret_nonce) = 12
        AND CHAR_LENGTH(secret_key_version) BETWEEN 1 AND 64)
);
