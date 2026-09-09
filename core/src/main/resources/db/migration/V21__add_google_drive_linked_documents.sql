ALTER TABLE google_drive_sources
    ADD COLUMN discovery_revision BIGINT NOT NULL DEFAULT 0 CHECK (discovery_revision >= 0),
    ADD COLUMN discovered_at TIMESTAMPTZ,
    ADD COLUMN discovery_scope_revision BIGINT,
    ADD COLUMN discovery_credential_revision BIGINT;

CREATE TABLE google_drive_linked_documents (
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    file_id VARCHAR(256) NOT NULL,
    name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(160) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('AVAILABLE', 'UNAVAILABLE', 'UNSUPPORTED')),
    covered_by_roots BOOLEAN NOT NULL,
    PRIMARY KEY (tenant_id, source_id, file_id),
    FOREIGN KEY (tenant_id, source_id) REFERENCES google_drive_sources (tenant_id, source_id) ON DELETE CASCADE
);

CREATE TABLE google_drive_link_origins (
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    file_id VARCHAR(256) NOT NULL,
    root_id VARCHAR(256) NOT NULL,
    parent_id VARCHAR(256) NOT NULL,
    parent_name VARCHAR(255) NOT NULL,
    location VARCHAR(512) NOT NULL,
    PRIMARY KEY (tenant_id, source_id, file_id, root_id, parent_id, location),
    FOREIGN KEY (tenant_id, source_id, file_id)
        REFERENCES google_drive_linked_documents (tenant_id, source_id, file_id) ON DELETE CASCADE
);

CREATE TABLE google_drive_link_approvals (
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    file_id VARCHAR(256) NOT NULL,
    PRIMARY KEY (tenant_id, source_id, file_id),
    FOREIGN KEY (tenant_id, source_id, file_id)
        REFERENCES google_drive_linked_documents (tenant_id, source_id, file_id) ON DELETE CASCADE
);

CREATE TABLE google_drive_discovery_errors (
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    file_id VARCHAR(256) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    code VARCHAR(64) NOT NULL CHECK (code LIKE 'SOURCE_GOOGLE_%'),
    PRIMARY KEY (tenant_id, source_id, file_id, code),
    FOREIGN KEY (tenant_id, source_id) REFERENCES google_drive_sources (tenant_id, source_id) ON DELETE CASCADE
);
