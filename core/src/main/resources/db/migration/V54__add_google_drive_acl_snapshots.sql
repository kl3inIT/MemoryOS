ALTER TABLE google_drive_membership ADD COLUMN content_provider_version TEXT;

CREATE TABLE google_drive_acl_snapshots (
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    file_id VARCHAR(256) NOT NULL CHECK (CHAR_LENGTH(BTRIM(file_id)) > 0),
    observation_revision BIGINT NOT NULL DEFAULT 0 CHECK (observation_revision >= 0),
    permissions_json JSONB,
    status VARCHAR(16) NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    last_attempt_at TIMESTAMPTZ NOT NULL,
    attempt_operation_id UUID NOT NULL,
    attempt_credential_id UUID NOT NULL,
    attempt_credential_revision BIGINT NOT NULL CHECK (attempt_credential_revision > 0),
    attempt_scope_revision BIGINT NOT NULL CHECK (attempt_scope_revision > 0),
    attempt_generation BIGINT NOT NULL CHECK (attempt_generation >= 0),
    last_success_at TIMESTAMPTZ,
    success_operation_id UUID,
    success_credential_id UUID,
    success_credential_revision BIGINT,
    success_scope_revision BIGINT,
    success_generation BIGINT,
    error_code VARCHAR(64),
    PRIMARY KEY (tenant_id, source_id, file_id),
    CONSTRAINT fk_google_drive_acl_source FOREIGN KEY (tenant_id, source_id)
        REFERENCES google_drive_sources (tenant_id, source_id) ON DELETE CASCADE,
    CONSTRAINT ck_google_drive_acl_success CHECK (
        (observation_revision = 0 AND permissions_json IS NULL AND last_success_at IS NULL
            AND success_operation_id IS NULL AND success_credential_id IS NULL
            AND success_credential_revision IS NULL AND success_scope_revision IS NULL AND success_generation IS NULL)
        OR (observation_revision > 0 AND permissions_json IS NOT NULL AND jsonb_typeof(permissions_json) = 'array'
            AND last_success_at IS NOT NULL AND success_operation_id IS NOT NULL AND success_credential_id IS NOT NULL
            AND success_credential_revision IS NOT NULL AND success_credential_revision > 0
            AND success_scope_revision IS NOT NULL AND success_scope_revision > 0
            AND success_generation IS NOT NULL AND success_generation >= 0)
    ),
    CONSTRAINT ck_google_drive_acl_attempt CHECK (
        (status = 'FAILED' AND error_code IS NOT NULL AND error_code ~ '^[A-Z][A-Z0-9_]{0,63}$')
        OR (status = 'SUCCEEDED' AND observation_revision > 0 AND error_code IS NULL
            AND last_attempt_at = last_success_at AND attempt_operation_id = success_operation_id
            AND attempt_credential_id = success_credential_id AND attempt_credential_revision = success_credential_revision
            AND attempt_scope_revision = success_scope_revision AND attempt_generation = success_generation)
    )
);
