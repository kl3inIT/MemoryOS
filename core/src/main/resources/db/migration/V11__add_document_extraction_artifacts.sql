CREATE TABLE document_processing_attempts (
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    operation_id UUID NOT NULL,
    profile TEXT NOT NULL,
    result_sha256 VARCHAR(64),
    PRIMARY KEY (tenant_id, operation_id),
    CHECK (octet_length(profile) BETWEEN 1 AND 1024),
    CHECK (result_sha256 IS NULL OR length(result_sha256) = 64)
);

CREATE TABLE document_extraction_artifacts (
    id UUID NOT NULL,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    object_key VARCHAR(240) NOT NULL UNIQUE,
    content_sha256 VARCHAR(64) NOT NULL CHECK (length(content_sha256) = 64),
    size_bytes BIGINT NOT NULL CHECK (size_bytes BETWEEN 1 AND 33554432),
    state VARCHAR(16) NOT NULL CHECK (state IN ('STAGED', 'ACTIVE', 'DELETING')),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    cleanup_token UUID,
    cleanup_until TIMESTAMP WITH TIME ZONE,
    write_complete BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (tenant_id, id)
);

ALTER TABLE document_versions ADD COLUMN processing_profile TEXT NOT NULL DEFAULT 'legacy-tika-v1';
ALTER TABLE document_versions ADD CONSTRAINT ck_document_version_profile_size
    CHECK (octet_length(processing_profile) BETWEEN 1 AND 1024);
ALTER TABLE document_versions ADD COLUMN extraction_artifact_id UUID;
ALTER TABLE document_versions ADD CONSTRAINT fk_document_version_extraction
    FOREIGN KEY (tenant_id, extraction_artifact_id)
    REFERENCES document_extraction_artifacts(tenant_id, id) ON DELETE RESTRICT;
ALTER TABLE document_versions DROP CONSTRAINT uq_document_versions_document_sha;
CREATE UNIQUE INDEX uq_document_version_processing ON document_versions
    (tenant_id, document_id, source_content_sha256, processing_profile);
CREATE INDEX ix_document_artifact_cleanup ON document_extraction_artifacts (expires_at);
