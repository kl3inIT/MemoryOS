CREATE TABLE stored_objects (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    object_key VARCHAR(240) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    declared_media_type VARCHAR(160) NOT NULL,
    size_bytes BIGINT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_stored_objects_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_stored_objects_object_key UNIQUE (object_key),
    CONSTRAINT fk_stored_objects_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT ck_stored_objects_key CHECK (CHAR_LENGTH(object_key) BETWEEN 1 AND 240),
    CONSTRAINT ck_stored_objects_filename CHECK (CHAR_LENGTH(filename) BETWEEN 1 AND 255),
    CONSTRAINT ck_stored_objects_media_type CHECK (CHAR_LENGTH(declared_media_type) BETWEEN 1 AND 160),
    CONSTRAINT ck_stored_objects_size CHECK (size_bytes BETWEEN 1 AND 10485760),
    CONSTRAINT ck_stored_objects_sha CHECK (CHAR_LENGTH(content_sha256) = 64 AND LOWER(content_sha256) = content_sha256),
    CONSTRAINT ck_stored_objects_state CHECK (state IN ('STAGED', 'ACTIVE', 'DELETE_PENDING'))
);

CREATE TABLE object_uploads (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    stored_object_id UUID,
    status VARCHAR(32) NOT NULL,
    verification_token UUID,
    verification_lease_until TIMESTAMP WITH TIME ZONE,
    verified_at TIMESTAMP WITH TIME ZONE,
    adoption_deadline TIMESTAMP WITH TIME ZONE,
    cleanup_token UUID,
    cleanup_lease_until TIMESTAMP WITH TIME ZONE,
    cleanup_attempts INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_object_uploads_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_object_uploads_stored_object UNIQUE (tenant_id, stored_object_id),
    CONSTRAINT fk_object_uploads_stored_object FOREIGN KEY (tenant_id, stored_object_id)
        REFERENCES stored_objects (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_object_uploads_status CHECK (status IN ('PENDING', 'VERIFYING', 'VERIFIED', 'ADOPTED', 'DISCARDED', 'CLEANING', 'EXPIRED')),
    CONSTRAINT ck_object_uploads_cleanup_attempts CHECK (cleanup_attempts >= 0),
    CONSTRAINT ck_object_uploads_verification_state CHECK (
        (status = 'VERIFYING' AND verification_token IS NOT NULL AND verification_lease_until IS NOT NULL)
        OR (status IN ('VERIFIED', 'ADOPTED', 'DISCARDED') AND verification_token IS NOT NULL AND verified_at IS NOT NULL AND adoption_deadline IS NOT NULL)
        OR (status IN ('PENDING', 'EXPIRED') AND verification_token IS NULL AND verification_lease_until IS NULL)
        OR status = 'CLEANING'
    ),
    CONSTRAINT ck_object_uploads_cleanup_state CHECK (
        (status = 'CLEANING' AND cleanup_token IS NOT NULL AND cleanup_lease_until IS NOT NULL)
        OR status <> 'CLEANING'
    ),
    CONSTRAINT ck_object_uploads_object_reference CHECK (
        (status = 'EXPIRED' AND stored_object_id IS NULL)
        OR (status <> 'EXPIRED' AND stored_object_id IS NOT NULL)
    )
);

CREATE TABLE source_uploads (
    tenant_id UUID NOT NULL,
    connector_credential_pair_id UUID NOT NULL,
    object_upload_id UUID NOT NULL,
    connector_item_id UUID,
    connector_item_version_id UUID,
    index_attempt_id UUID,
    finalized_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_source_uploads PRIMARY KEY (tenant_id, connector_credential_pair_id, object_upload_id),
    CONSTRAINT uq_source_uploads_object_upload UNIQUE (tenant_id, object_upload_id),
    CONSTRAINT fk_source_uploads_pair FOREIGN KEY (tenant_id, connector_credential_pair_id)
        REFERENCES connector_credential_pairs (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_source_uploads_upload FOREIGN KEY (tenant_id, object_upload_id)
        REFERENCES object_uploads (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_source_uploads_item FOREIGN KEY (tenant_id, connector_item_id)
        REFERENCES connector_items (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_source_uploads_version FOREIGN KEY (tenant_id, connector_item_version_id)
        REFERENCES connector_item_versions (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_source_uploads_attempt FOREIGN KEY (tenant_id, index_attempt_id)
        REFERENCES index_attempts (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_source_uploads_receipt CHECK (
        (connector_item_id IS NULL AND connector_item_version_id IS NULL AND index_attempt_id IS NULL AND finalized_at IS NULL)
        OR (connector_item_id IS NOT NULL AND connector_item_version_id IS NOT NULL AND index_attempt_id IS NOT NULL AND finalized_at IS NOT NULL)
    )
);

ALTER TABLE connector_item_versions ADD COLUMN stored_object_id UUID;
ALTER TABLE connector_item_versions DROP CONSTRAINT ck_item_versions_bytes;
ALTER TABLE connector_item_versions DROP COLUMN content_bytes;
ALTER TABLE connector_item_versions ALTER COLUMN stored_object_id SET NOT NULL;
ALTER TABLE connector_item_versions ADD CONSTRAINT fk_item_versions_stored_object
    FOREIGN KEY (tenant_id, stored_object_id)
    REFERENCES stored_objects (tenant_id, id) ON DELETE RESTRICT;
ALTER TABLE connector_item_versions ADD CONSTRAINT uq_item_versions_stored_object
    UNIQUE (tenant_id, stored_object_id);

CREATE INDEX ix_object_uploads_verification
    ON object_uploads (status, verification_lease_until, updated_at);
CREATE INDEX ix_object_uploads_cleanup
    ON object_uploads (status, cleanup_lease_until, updated_at);
CREATE INDEX ix_stored_objects_expiry
    ON stored_objects (state, expires_at, created_at);
