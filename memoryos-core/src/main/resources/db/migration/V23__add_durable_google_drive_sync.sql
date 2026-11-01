ALTER TABLE connector_items ADD COLUMN provider_file_id VARCHAR(256);
ALTER TABLE connector_items DROP CONSTRAINT uq_items_connector_sha;
CREATE UNIQUE INDEX uq_items_file_sha ON connector_items (tenant_id, connector_id, content_sha256)
    WHERE provider_file_id IS NULL;
CREATE UNIQUE INDEX uq_items_provider_file ON connector_items (tenant_id, connector_id, provider_file_id)
    WHERE provider_file_id IS NOT NULL;
ALTER TABLE connector_item_versions DROP CONSTRAINT uq_item_versions_item_sha;
ALTER TABLE connector_item_versions ADD COLUMN input_format VARCHAR(32) NOT NULL DEFAULT 'BINARY';
ALTER TABLE connector_item_versions ADD COLUMN provider_file_id VARCHAR(256);
ALTER TABLE connector_item_versions ADD COLUMN provider_version VARCHAR(512);
ALTER TABLE connector_item_versions ADD COLUMN source_url VARCHAR(2048);
ALTER TABLE connector_item_versions ADD COLUMN scope_revision BIGINT;
ALTER TABLE connector_item_versions ADD COLUMN credential_revision BIGINT;
ALTER TABLE connector_item_versions DROP CONSTRAINT ck_item_versions_size;
ALTER TABLE connector_item_versions ADD CONSTRAINT ck_item_versions_input CHECK (
    input_format IN ('BINARY', 'GOOGLE_SHEETS', 'GOOGLE_DOCS')
    AND size_bytes BETWEEN 1 AND CASE WHEN input_format = 'BINARY' THEN 10485760 ELSE 33554432 END
    AND ((provider_file_id IS NULL AND input_format = 'BINARY' AND scope_revision IS NULL AND credential_revision IS NULL)
      OR (provider_file_id IS NOT NULL AND scope_revision > 0 AND credential_revision > 0))
);

ALTER TABLE index_attempts ADD COLUMN deferred_attempts INTEGER NOT NULL DEFAULT 0
    CHECK (deferred_attempts >= 0 AND deferred_attempts <= processing_attempts);

CREATE TABLE google_drive_sources (
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    generation BIGINT NOT NULL DEFAULT 0 CHECK (generation >= 0),
    changes_token TEXT,
    last_synced_at TIMESTAMPTZ,
    next_sync_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    error_code VARCHAR(64),
    PRIMARY KEY (tenant_id, source_id),
    FOREIGN KEY (tenant_id, source_id) REFERENCES connector_credential_pairs (tenant_id, id) ON DELETE CASCADE
);
CREATE TABLE google_drive_roots (
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    file_id VARCHAR(256) NOT NULL,
    name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(160) NOT NULL,
    PRIMARY KEY (tenant_id, source_id, file_id),
    FOREIGN KEY (tenant_id, source_id) REFERENCES google_drive_sources (tenant_id, source_id) ON DELETE CASCADE
);
CREATE TABLE source_sync_attempts (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    scope_revision BIGINT NOT NULL,
    credential_revision BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    phase VARCHAR(16) NOT NULL DEFAULT 'START' CHECK (phase IN ('START', 'SCAN', 'CHANGES', 'FINISH')),
    full_scan BOOLEAN NOT NULL,
    page_token TEXT,
    final_token TEXT,
    restart_count INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED'
        CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'SUPERSEDED', 'CANCELLED')),
    claim_token UUID,
    lease_expires_at TIMESTAMPTZ,
    delivery_id UUID,
    dispatch_token UUID,
    dispatch_lease_expires_at TIMESTAMPTZ,
    redis_message_id VARCHAR(64),
    next_dispatch_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    dispatched_at TIMESTAMPTZ,
    dispatch_attempts INTEGER NOT NULL DEFAULT 0,
    processing_attempts INTEGER NOT NULL DEFAULT 0,
    failure_attempts INTEGER NOT NULL DEFAULT 0,
    last_transport_error VARCHAR(64),
    error_code VARCHAR(64),
    origin_trace_id VARCHAR(32),
    origin_span_id VARCHAR(16),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, source_id) REFERENCES google_drive_sources (tenant_id, source_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uq_source_sync_live ON source_sync_attempts (tenant_id, source_id)
    WHERE status IN ('NOT_STARTED', 'IN_PROGRESS');
CREATE INDEX ix_source_sync_dispatch ON source_sync_attempts (status, next_dispatch_at, dispatch_lease_expires_at, created_at);
CREATE TABLE google_drive_frontier (
    tenant_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    file_id VARCHAR(256) NOT NULL,
    task_kind VARCHAR(16) NOT NULL CHECK (task_kind IN ('FILE', 'FOLDER')),
    page_token TEXT,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'DONE', 'FAILED', 'UNSUPPORTED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, attempt_id, file_id, task_kind),
    FOREIGN KEY (tenant_id, attempt_id) REFERENCES source_sync_attempts (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX ix_google_frontier_pending ON google_drive_frontier (tenant_id, attempt_id, state, attempts, created_at);
CREATE TABLE google_drive_membership (
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    file_id VARCHAR(256) NOT NULL,
    root_id VARCHAR(256),
    generation BIGINT NOT NULL,
    eligible BOOLEAN NOT NULL DEFAULT FALSE,
    excluded BOOLEAN NOT NULL DEFAULT FALSE,
    provider_version VARCHAR(512),
    error_code VARCHAR(64),
    PRIMARY KEY (tenant_id, source_id, file_id),
    FOREIGN KEY (tenant_id, source_id) REFERENCES google_drive_sources (tenant_id, source_id) ON DELETE CASCADE
);
