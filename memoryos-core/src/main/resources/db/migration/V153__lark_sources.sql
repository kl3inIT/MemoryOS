-- MEM-245: Lark Suite Sources. A Source names Wiki spaces and Drive folders shared with the credential's app; a
-- run lists them whole, so what it did not see can be removed. Docx and Sheets are stored as JSON snapshots.
ALTER TABLE connectors DROP CONSTRAINT ck_connectors_type;
ALTER TABLE connectors ADD CONSTRAINT ck_connectors_type
    CHECK (connector_type IN ('FILE', 'GOOGLE_DRIVE', 'SHAREPOINT', 'LARK'));

ALTER TABLE source_selection_operations DROP CONSTRAINT ck_source_selection_type;
ALTER TABLE source_selection_operations ADD CONSTRAINT ck_source_selection_type
    CHECK (source_type IN ('GOOGLE_DRIVE', 'SHAREPOINT', 'LARK'));

ALTER TABLE connector_item_versions DROP CONSTRAINT ck_item_versions_input;
ALTER TABLE connector_item_versions ADD CONSTRAINT ck_item_versions_input CHECK (
    input_format IN ('BINARY', 'GOOGLE_SHEETS', 'GOOGLE_DOCS', 'SHAREPOINT_PAGE', 'LARK_DOCX', 'LARK_SHEET')
    AND size_bytes BETWEEN 1 AND CASE WHEN input_format = 'BINARY' THEN 104857600 ELSE 33554432 END
    AND ((provider_file_id IS NULL AND input_format = 'BINARY' AND scope_revision IS NULL AND credential_revision IS NULL)
      OR (provider_file_id IS NOT NULL AND scope_revision > 0 AND credential_revision > 0))
);

-- A changed, unusable or deleted credential ends the verifications it was accepted for, with the code each
-- provider's clients handle.
CREATE OR REPLACE FUNCTION fence_selection_credential() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR NEW.credential_revision <> OLD.credential_revision OR NEW.status <> 'ACTIVE' THEN
        UPDATE source_selection_operations SET status = 'CANCELLED',
            error_code = CASE source_type
                WHEN 'GOOGLE_DRIVE' THEN 'SOURCE_GOOGLE_CREDENTIAL_CHANGED'
                WHEN 'SHAREPOINT' THEN 'SOURCE_SHAREPOINT_CREDENTIAL_CHANGED'
                WHEN 'LARK' THEN 'SOURCE_LARK_CREDENTIAL_CHANGED'
                ELSE 'SOURCE_CREDENTIAL_CHANGED' END,
            completed_at = CURRENT_TIMESTAMP, claim_token = NULL, lease_expires_at = NULL
        WHERE tenant_id = OLD.tenant_id AND credential_id = OLD.id AND status IN ('NOT_STARTED', 'IN_PROGRESS');
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION source_run_error_stage(code TEXT) RETURNS TEXT
LANGUAGE SQL IMMUTABLE AS $$
    SELECT CASE
        WHEN code LIKE 'SOURCE_STORAGE_READ_%' THEN 'STORAGE_READ'
        WHEN code LIKE 'SOURCE_STORAGE_WRITE_%' THEN 'STORAGE_WRITE'
        WHEN code LIKE 'SOURCE_EXTRACTION_%' THEN 'EXTRACTION'
        WHEN code LIKE 'SOURCE_PUBLICATION_%' THEN 'PUBLICATION'
        WHEN code LIKE 'SOURCE_GOOGLE_%' THEN 'PROVIDER'
        WHEN code LIKE 'SOURCE_SHAREPOINT_%' THEN 'PROVIDER'
        WHEN code LIKE 'SOURCE_LARK_%' THEN 'PROVIDER'
        ELSE 'SYSTEM' END
$$;

CREATE TABLE lark_sources (
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, source_id),
    FOREIGN KEY (tenant_id, source_id) REFERENCES connector_credential_pairs (tenant_id, id) ON DELETE CASCADE,
    -- A provider Source row never exists without its synchronization state.
    CONSTRAINT fk_lark_source_sync_state FOREIGN KEY (tenant_id, source_id)
        REFERENCES source_sync_state (tenant_id, source_id) ON DELETE CASCADE
);

-- What the Source reads: a Wiki space by its ID, or a Drive folder by its token.
CREATE TABLE lark_roots (
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    position INTEGER NOT NULL CHECK (position >= 0),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('WIKI_SPACE', 'DRIVE_FOLDER')),
    token VARCHAR(128) NOT NULL CHECK (CHAR_LENGTH(BTRIM(token)) BETWEEN 1 AND 128),
    display_name VARCHAR(255),
    PRIMARY KEY (tenant_id, source_id, position),
    CONSTRAINT uq_lark_root UNIQUE (tenant_id, source_id, kind, token),
    FOREIGN KEY (tenant_id, source_id) REFERENCES lark_sources (tenant_id, source_id) ON DELETE CASCADE
);

-- What only Lark verifies: the schedule the accepted scope asked for.
CREATE TABLE lark_selection_details (
    tenant_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    sync_interval_minutes INTEGER NOT NULL CHECK (sync_interval_minutes >= 1),
    PRIMARY KEY (tenant_id, operation_id),
    FOREIGN KEY (tenant_id, operation_id) REFERENCES source_selection_operations (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE lark_selection_entries (
    tenant_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    position INTEGER NOT NULL CHECK (position >= 0),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('WIKI_SPACE', 'DRIVE_FOLDER')),
    token VARCHAR(128) NOT NULL,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    display_name VARCHAR(255),
    PRIMARY KEY (tenant_id, operation_id, position),
    FOREIGN KEY (tenant_id, operation_id) REFERENCES lark_selection_details (tenant_id, operation_id) ON DELETE CASCADE
);

-- Objects the Source holds, keyed by the object token Lark's own APIs take. `last_seen_run` is the run that last
-- listed the object; a completed run removes what it did not list.
CREATE TABLE lark_items (
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    provider_file_id VARCHAR(128) NOT NULL,
    object_type VARCHAR(16) NOT NULL,
    name VARCHAR(400),
    content_version VARCHAR(128),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_run UUID,
    PRIMARY KEY (tenant_id, source_id, provider_file_id),
    FOREIGN KEY (tenant_id, source_id) REFERENCES lark_sources (tenant_id, source_id) ON DELETE CASCADE
);
CREATE INDEX ix_lark_items_run ON lark_items (tenant_id, source_id, last_seen_run);

CREATE TABLE lark_sync_runs (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    source_sync_attempt_id UUID,
    -- Where a handed-back run resumes: the root being walked, the container being listed (a Wiki node or a folder;
    -- NULL is the root itself), its page, and the containers still waiting, one per line.
    checkpoint_root INTEGER NOT NULL DEFAULT 0 CHECK (checkpoint_root >= 0),
    checkpoint_container VARCHAR(128),
    checkpoint_page TEXT,
    checkpoint_pending TEXT,
    listing_complete BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS'
        CHECK (status IN ('IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, source_id) REFERENCES lark_sources (tenant_id, source_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uq_lark_run_live ON lark_sync_runs (tenant_id, source_id) WHERE status = 'IN_PROGRESS';
