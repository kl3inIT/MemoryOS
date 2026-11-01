CREATE TABLE connectors (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    connector_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_connectors_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT fk_connectors_organization FOREIGN KEY (organization_id)
        REFERENCES organizations (id) ON DELETE RESTRICT,
    CONSTRAINT ck_connectors_type CHECK (connector_type = 'FILE'),
    CONSTRAINT ck_connectors_status CHECK (status IN ('ACTIVE', 'DELETING')),
    CONSTRAINT ck_connectors_name CHECK (CHAR_LENGTH(name) BETWEEN 1 AND 120)
);

CREATE TABLE credentials (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    credential_kind VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_credentials_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT uq_credentials_organization_kind UNIQUE (organization_id, credential_kind),
    CONSTRAINT fk_credentials_organization FOREIGN KEY (organization_id)
        REFERENCES organizations (id) ON DELETE RESTRICT,
    CONSTRAINT ck_credentials_kind CHECK (credential_kind = 'NO_AUTH'),
    CONSTRAINT ck_credentials_status CHECK (status = 'ACTIVE')
);

CREATE TABLE connector_credential_pairs (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    connector_id UUID NOT NULL,
    credential_id UUID NOT NULL,
    access_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    pair_sequence BIGINT NOT NULL DEFAULT 0,
    document_count BIGINT NOT NULL DEFAULT 0,
    last_succeeded_at TIMESTAMP WITH TIME ZONE,
    error_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_pairs_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT uq_pairs_connector_credential UNIQUE (organization_id, connector_id, credential_id),
    CONSTRAINT fk_pairs_connector FOREIGN KEY (organization_id, connector_id)
        REFERENCES connectors (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_pairs_credential FOREIGN KEY (organization_id, credential_id)
        REFERENCES credentials (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_pairs_access CHECK (access_type = 'PUBLIC'),
    CONSTRAINT ck_pairs_status CHECK (status IN ('NOT_STARTED', 'INDEXING', 'ACTIVE', 'FAILED', 'DELETING')),
    CONSTRAINT ck_pairs_sequence CHECK (pair_sequence >= 0),
    CONSTRAINT ck_pairs_document_count CHECK (document_count >= 0)
);

CREATE TABLE connector_items (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    connector_id UUID NOT NULL,
    current_version_id UUID,
    content_sha256 CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_items_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT uq_items_connector_sha UNIQUE (organization_id, connector_id, content_sha256),
    CONSTRAINT fk_items_connector FOREIGN KEY (organization_id, connector_id)
        REFERENCES connectors (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_items_sha CHECK (CHAR_LENGTH(content_sha256) = 64),
    CONSTRAINT ck_items_status CHECK (status IN ('PENDING', 'INDEXED', 'FAILED', 'DELETING'))
);

CREATE TABLE connector_item_versions (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    connector_id UUID NOT NULL,
    connector_item_id UUID NOT NULL,
    revision_number BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    content_bytes BYTEA NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_item_versions_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT uq_item_versions_item_revision UNIQUE (organization_id, connector_item_id, revision_number),
    CONSTRAINT uq_item_versions_item_sha UNIQUE (organization_id, connector_item_id, content_sha256),
    CONSTRAINT fk_item_versions_item FOREIGN KEY (organization_id, connector_item_id)
        REFERENCES connector_items (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_item_versions_connector FOREIGN KEY (organization_id, connector_id)
        REFERENCES connectors (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_item_versions_revision CHECK (revision_number > 0),
    CONSTRAINT ck_item_versions_filename CHECK (CHAR_LENGTH(filename) BETWEEN 1 AND 255),
    CONSTRAINT ck_item_versions_sha CHECK (CHAR_LENGTH(content_sha256) = 64),
    CONSTRAINT ck_item_versions_size CHECK (size_bytes BETWEEN 1 AND 10485760),
    CONSTRAINT ck_item_versions_bytes CHECK (OCTET_LENGTH(content_bytes) = size_bytes)
);

ALTER TABLE connector_items ADD CONSTRAINT fk_items_current_version
    FOREIGN KEY (organization_id, current_version_id)
    REFERENCES connector_item_versions (organization_id, id) ON DELETE RESTRICT;

CREATE TABLE index_attempts (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    connector_id UUID NOT NULL,
    connector_credential_pair_id UUID NOT NULL,
    connector_item_id UUID NOT NULL,
    connector_item_version_id UUID NOT NULL,
    pair_sequence BIGINT NOT NULL,
    item_sequence BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    claim_token UUID,
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    error_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_index_attempts_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT uq_index_attempts_pair_sequence UNIQUE (organization_id, connector_credential_pair_id, pair_sequence),
    CONSTRAINT fk_index_attempts_pair FOREIGN KEY (organization_id, connector_credential_pair_id)
        REFERENCES connector_credential_pairs (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_index_attempts_item FOREIGN KEY (organization_id, connector_item_id)
        REFERENCES connector_items (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_index_attempts_version FOREIGN KEY (organization_id, connector_item_version_id)
        REFERENCES connector_item_versions (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_index_attempts_status CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'SUPERSEDED', 'CANCELLED')),
    CONSTRAINT ck_index_attempts_sequences CHECK (pair_sequence > 0 AND item_sequence > 0)
);

CREATE TABLE documents (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_version_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_documents_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT fk_documents_organization FOREIGN KEY (organization_id)
        REFERENCES organizations (id) ON DELETE RESTRICT,
    CONSTRAINT ck_documents_status CHECK (status IN ('ELIGIBLE', 'INELIGIBLE'))
);

CREATE TABLE document_versions (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    document_id UUID NOT NULL,
    version_number BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    media_type VARCHAR(160) NOT NULL,
    normalized_text TEXT NOT NULL,
    source_content_sha256 CHAR(64) NOT NULL,
    metadata_json TEXT NOT NULL DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_document_versions_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT uq_document_versions_document_number UNIQUE (organization_id, document_id, version_number),
    CONSTRAINT uq_document_versions_document_sha UNIQUE (organization_id, document_id, source_content_sha256),
    CONSTRAINT fk_document_versions_document FOREIGN KEY (organization_id, document_id)
        REFERENCES documents (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_document_versions_text CHECK (CHAR_LENGTH(normalized_text) <= 2000000),
    CONSTRAINT ck_document_versions_sha CHECK (CHAR_LENGTH(source_content_sha256) = 64)
);

ALTER TABLE documents ADD CONSTRAINT fk_documents_current_version
    FOREIGN KEY (organization_id, current_version_id)
    REFERENCES document_versions (organization_id, id) ON DELETE RESTRICT;

CREATE TABLE documents_by_connector_credential_pair (
    organization_id UUID NOT NULL,
    connector_id UUID NOT NULL,
    connector_credential_pair_id UUID NOT NULL,
    document_id UUID NOT NULL,
    connector_item_id UUID NOT NULL,
    retrieval_eligible BOOLEAN NOT NULL,
    first_indexed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_indexed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_documents_by_pair PRIMARY KEY (organization_id, connector_credential_pair_id, document_id),
    CONSTRAINT uq_documents_by_pair_item UNIQUE (organization_id, connector_credential_pair_id, connector_item_id),
    CONSTRAINT fk_documents_by_pair_pair FOREIGN KEY (organization_id, connector_credential_pair_id)
        REFERENCES connector_credential_pairs (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_documents_by_pair_document FOREIGN KEY (organization_id, document_id)
        REFERENCES documents (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_documents_by_pair_item FOREIGN KEY (organization_id, connector_item_id)
        REFERENCES connector_items (organization_id, id) ON DELETE RESTRICT
);

CREATE TABLE connector_cleanup_attempts (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    operation VARCHAR(32) NOT NULL,
    target_key VARCHAR(200) NOT NULL,
    target_pair_id UUID,
    target_item_id UUID,
    status VARCHAR(32) NOT NULL,
    claim_token UUID,
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    error_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_cleanup_attempts_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT uq_cleanup_attempts_target UNIQUE (organization_id, operation, target_key),
    CONSTRAINT fk_cleanup_attempts_organization FOREIGN KEY (organization_id)
        REFERENCES organizations (id) ON DELETE RESTRICT,
    CONSTRAINT ck_cleanup_attempts_operation CHECK (operation IN ('REMOVE_ITEM', 'DELETE_SOURCE')),
    CONSTRAINT ck_cleanup_attempts_status CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'SUPERSEDED')),
    CONSTRAINT ck_cleanup_attempts_target CHECK (
        (operation = 'REMOVE_ITEM' AND target_item_id IS NOT NULL AND target_pair_id IS NOT NULL)
        OR (operation = 'DELETE_SOURCE' AND target_pair_id IS NOT NULL AND target_item_id IS NULL)
    )
);

CREATE INDEX ix_connectors_organization_status ON connectors (organization_id, status, created_at);
CREATE INDEX ix_pairs_organization_status ON connector_credential_pairs (organization_id, status, created_at);
CREATE INDEX ix_items_connector_status ON connector_items (organization_id, connector_id, status, created_at);
CREATE INDEX ix_index_attempts_claim ON index_attempts (status, lease_expires_at, created_at);
CREATE INDEX ix_index_attempts_pair_status ON index_attempts (organization_id, connector_credential_pair_id, status, pair_sequence);
CREATE INDEX ix_documents_by_pair_document ON documents_by_connector_credential_pair (organization_id, document_id, retrieval_eligible);
CREATE INDEX ix_cleanup_attempts_claim ON connector_cleanup_attempts (status, lease_expires_at, created_at);
