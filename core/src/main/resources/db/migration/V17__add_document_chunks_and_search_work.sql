ALTER TABLE documents ADD COLUMN content_generation UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE documents ADD COLUMN chunk_count INTEGER;
ALTER TABLE documents ADD COLUMN chunk_generation UUID;
ALTER TABLE documents ADD COLUMN searchable_generation UUID;
ALTER TABLE documents ADD COLUMN search_index_identity VARCHAR(160);
ALTER TABLE documents ADD COLUMN search_error_code VARCHAR(64);
ALTER TABLE documents ADD COLUMN chunk_convention VARCHAR(80);

CREATE TABLE document_chunks (
    tenant_id UUID NOT NULL,
    document_id UUID NOT NULL,
    generation UUID NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    content TEXT NOT NULL,
    headings_json TEXT NOT NULL,
    block_index INTEGER NOT NULL CHECK (block_index >= 0),
    part INTEGER NOT NULL CHECK (part >= 0),
    provenance_json TEXT NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    token_count INTEGER NOT NULL CHECK (token_count BETWEEN 1 AND 768),
    PRIMARY KEY (tenant_id, document_id, ordinal),
    FOREIGN KEY (tenant_id, document_id) REFERENCES documents(tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE document_artifact_readers (
    tenant_id UUID NOT NULL,
    artifact_id UUID NOT NULL,
    reader_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (reader_id),
    FOREIGN KEY (tenant_id, artifact_id) REFERENCES document_extraction_artifacts(tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX document_artifact_readers_live ON document_artifact_readers(tenant_id, artifact_id, expires_at);

-- Ingestion owns execution; vectors never enter PostgreSQL.
-- No document FK: deletion work survives removal of the current Document.
CREATE TABLE search_index_operations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    document_id UUID NOT NULL,
    generation UUID NOT NULL,
    action VARCHAR(8) NOT NULL CHECK (action IN ('INDEX','DELETE')),
    index_identity VARCHAR(160) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'NOT_STARTED'
        CHECK (status IN ('NOT_STARTED','IN_PROGRESS','SUCCESS','FAILED','CANCELLED')),
    claim_token UUID,
    lease_expires_at TIMESTAMPTZ,
    processing_attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    error_code VARCHAR(64),
    delivery_id UUID,
    dispatch_token UUID,
    dispatch_lease_expires_at TIMESTAMPTZ,
    dispatch_attempts INTEGER NOT NULL DEFAULT 0,
    redis_message_id VARCHAR(64),
    dispatched_at TIMESTAMPTZ,
    next_dispatch_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_transport_error VARCHAR(64),
    origin_trace_id VARCHAR(32),
    origin_span_id VARCHAR(16),
    UNIQUE (tenant_id, document_id, generation, action, index_identity)
);
CREATE INDEX search_index_operations_dispatch ON search_index_operations(next_dispatch_at, created_at)
    WHERE status IN ('NOT_STARTED','IN_PROGRESS');
