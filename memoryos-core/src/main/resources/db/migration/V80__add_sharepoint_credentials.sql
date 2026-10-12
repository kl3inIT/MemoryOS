ALTER TABLE credentials DROP CONSTRAINT ck_credentials_kind;
ALTER TABLE credentials ADD CONSTRAINT ck_credentials_kind CHECK (credential_kind IN ('NO_AUTH', 'GOOGLE_OAUTH', 'SHAREPOINT_APP'));
ALTER TABLE credentials DROP CONSTRAINT ck_credentials_status;
ALTER TABLE credentials ADD CONSTRAINT ck_credentials_status CHECK (
    (credential_kind = 'NO_AUTH' AND status = 'ACTIVE') OR
    (credential_kind = 'GOOGLE_OAUTH' AND status IN ('ACTIVE', 'NEEDS_REAUTHORIZATION', 'REVOKED')) OR
    (credential_kind = 'SHAREPOINT_APP' AND status IN ('ACTIVE', 'NEEDS_UPDATE'))
);
ALTER TABLE credentials DROP CONSTRAINT ck_credential_owner_kind;
ALTER TABLE credentials ADD CONSTRAINT ck_credential_owner_kind
    CHECK (owner_actor_id IS NULL OR credential_kind IN ('GOOGLE_OAUTH', 'SHAREPOINT_APP'));

CREATE TABLE sharepoint_credentials (
    tenant_id UUID NOT NULL,
    credential_id UUID NOT NULL,
    directory_id UUID NOT NULL,
    client_id UUID NOT NULL,
    cloud VARCHAR(32) NOT NULL,
    auth_method VARCHAR(32) NOT NULL,
    connection_status VARCHAR(32) NOT NULL,
    credential_revision BIGINT NOT NULL DEFAULT 1,
    payload_revision BIGINT NOT NULL DEFAULT 1,
    secret_ciphertext BYTEA,
    secret_nonce BYTEA,
    secret_key_version VARCHAR(64),
    private_key_ciphertext BYTEA,
    private_key_nonce BYTEA,
    private_key_version VARCHAR(64),
    certificate_der BYTEA,
    certificate_thumbprint VARCHAR(40),
    certificate_not_after TIMESTAMP WITH TIME ZONE,
    tenant_host VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, credential_id),
    CONSTRAINT fk_sharepoint_credential FOREIGN KEY (tenant_id, credential_id)
        REFERENCES credentials (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_sharepoint_revision CHECK (credential_revision > 0 AND payload_revision > 0),
    CONSTRAINT ck_sharepoint_cloud CHECK (cloud = 'GLOBAL'),
    CONSTRAINT ck_sharepoint_auth_method CHECK (auth_method IN ('CLIENT_SECRET', 'CERTIFICATE')),
    CONSTRAINT ck_sharepoint_connection CHECK (connection_status IN ('ACTIVE', 'NEEDS_UPDATE')),
    CONSTRAINT ck_sharepoint_tenant_host CHECK (
        tenant_host IS NULL OR CHAR_LENGTH(tenant_host) BETWEEN 1 AND 255),
    -- One authentication payload per credential: the unused half stays entirely NULL.
    CONSTRAINT ck_sharepoint_secret_envelope CHECK (
        (auth_method = 'CLIENT_SECRET'
            AND secret_ciphertext IS NOT NULL AND OCTET_LENGTH(secret_ciphertext) BETWEEN 17 AND 1040
            AND secret_nonce IS NOT NULL AND OCTET_LENGTH(secret_nonce) = 12
            AND secret_key_version IS NOT NULL AND CHAR_LENGTH(secret_key_version) BETWEEN 1 AND 64)
        OR (auth_method = 'CERTIFICATE'
            AND secret_ciphertext IS NULL AND secret_nonce IS NULL AND secret_key_version IS NULL)
    ),
    CONSTRAINT ck_sharepoint_certificate_envelope CHECK (
        (auth_method = 'CERTIFICATE'
            AND private_key_ciphertext IS NOT NULL AND OCTET_LENGTH(private_key_ciphertext) BETWEEN 17 AND 16400
            AND private_key_nonce IS NOT NULL AND OCTET_LENGTH(private_key_nonce) = 12
            AND private_key_version IS NOT NULL AND CHAR_LENGTH(private_key_version) BETWEEN 1 AND 64
            AND certificate_der IS NOT NULL AND OCTET_LENGTH(certificate_der) BETWEEN 1 AND 16384
            AND certificate_thumbprint IS NOT NULL AND certificate_thumbprint ~ '^[0-9A-F]{40}$'
            AND certificate_not_after IS NOT NULL)
        OR (auth_method = 'CLIENT_SECRET'
            AND private_key_ciphertext IS NULL AND private_key_nonce IS NULL AND private_key_version IS NULL
            AND certificate_der IS NULL AND certificate_thumbprint IS NULL AND certificate_not_after IS NULL)
    )
);
