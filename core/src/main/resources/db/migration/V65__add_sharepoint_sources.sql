ALTER TABLE connectors DROP CONSTRAINT ck_connectors_type;
ALTER TABLE connectors ADD CONSTRAINT ck_connectors_type CHECK (connector_type IN ('FILE', 'GOOGLE_DRIVE', 'SHAREPOINT'));

CREATE TABLE sharepoint_sources (
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    scope_mode VARCHAR(16) NOT NULL CHECK (scope_mode IN ('ALL_SITES', 'SPECIFIC')),
    include_documents BOOLEAN NOT NULL DEFAULT TRUE,
    include_pages BOOLEAN NOT NULL DEFAULT FALSE,
    sync_interval_minutes INTEGER NOT NULL DEFAULT 30 CHECK (sync_interval_minutes >= 1),
    prune_interval_hours INTEGER NOT NULL DEFAULT 168
        CHECK (prune_interval_hours = 0 OR prune_interval_hours BETWEEN 1 AND 8760),
    scope_revision BIGINT NOT NULL DEFAULT 1 CHECK (scope_revision > 0),
    schedule_revision BIGINT NOT NULL DEFAULT 1 CHECK (schedule_revision > 0),
    -- Bumped whenever a run is enqueued, so an older attempt cannot write after a newer one starts.
    generation BIGINT NOT NULL DEFAULT 0 CHECK (generation >= 0),
    sync_paused BOOLEAN NOT NULL DEFAULT FALSE,
    tenant_host VARCHAR(255),
    -- End of the window of the last successful refresh; the next window starts here minus the overlap.
    refresh_window_end TIMESTAMPTZ,
    last_synced_at TIMESTAMPTZ,
    last_pruned_at TIMESTAMPTZ,
    next_sync_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_prune_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    error_code VARCHAR(64),
    PRIMARY KEY (tenant_id, source_id),
    CONSTRAINT ck_sharepoint_source_content CHECK (include_documents OR include_pages),
    FOREIGN KEY (tenant_id, source_id) REFERENCES connector_credential_pairs (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE sharepoint_roots (
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    position INTEGER NOT NULL CHECK (position >= 0),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('SITE', 'LIBRARY', 'FOLDER')),
    url VARCHAR(2048) NOT NULL,
    site_id VARCHAR(512),
    drive_id VARCHAR(512),
    item_id VARCHAR(512),
    display_name VARCHAR(255),
    -- A newly added root starts from the epoch so its existing content is collected once.
    full_refresh_pending BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (tenant_id, source_id, position),
    CONSTRAINT uq_sharepoint_root_url UNIQUE (tenant_id, source_id, url),
    CONSTRAINT ck_sharepoint_root_resolution CHECK (
        (kind = 'SITE' AND drive_id IS NULL AND item_id IS NULL)
        OR (kind = 'LIBRARY' AND item_id IS NULL)
        OR kind = 'FOLDER'
    ),
    FOREIGN KEY (tenant_id, source_id) REFERENCES sharepoint_sources (tenant_id, source_id) ON DELETE CASCADE
);

CREATE TABLE sharepoint_exclusions (
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('SITE', 'PATH')),
    position INTEGER NOT NULL CHECK (position >= 0),
    pattern VARCHAR(512) NOT NULL CHECK (CHAR_LENGTH(BTRIM(pattern)) BETWEEN 1 AND 512),
    PRIMARY KEY (tenant_id, source_id, kind, position),
    FOREIGN KEY (tenant_id, source_id) REFERENCES sharepoint_sources (tenant_id, source_id) ON DELETE CASCADE
);

-- Items MemoryOS holds for a Source, keyed by the Graph drive item or page identifier. The identifier is
-- what a delta tombstone carries, so a removed item can be matched without a name.
CREATE TABLE sharepoint_items (
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    provider_file_id VARCHAR(512) NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('FILE', 'PAGE')),
    drive_id VARCHAR(512),
    site_id VARCHAR(512),
    name VARCHAR(400),
    path VARCHAR(2048),
    content_version VARCHAR(512),
    e_tag VARCHAR(512),
    size_bytes BIGINT,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_prune_run UUID,
    PRIMARY KEY (tenant_id, source_id, provider_file_id),
    FOREIGN KEY (tenant_id, source_id) REFERENCES sharepoint_sources (tenant_id, source_id) ON DELETE CASCADE
);
CREATE INDEX ix_sharepoint_items_prune ON sharepoint_items (tenant_id, source_id, last_seen_prune_run);

CREATE TABLE sharepoint_sync_runs (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    source_sync_attempt_id UUID,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('REFRESH', 'PRUNE')),
    window_start TIMESTAMPTZ,
    window_end TIMESTAMPTZ NOT NULL,
    -- Drive or site the run stopped at, so a handed-back run resumes instead of restarting.
    checkpoint_drive_id VARCHAR(512),
    checkpoint_site_id VARCHAR(512),
    checkpoint_link TEXT,
    listing_complete BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS'
        CHECK (status IN ('IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, source_id) REFERENCES sharepoint_sources (tenant_id, source_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uq_sharepoint_run_live ON sharepoint_sync_runs (tenant_id, source_id) WHERE status = 'IN_PROGRESS';

-- Sync attempts were tied to Google Drive Sources; every connector writes them, so they now reference the
-- Source itself. Deleting a Source still removes its attempts.
ALTER TABLE source_sync_attempts DROP CONSTRAINT source_sync_attempts_tenant_id_source_id_fkey;
ALTER TABLE source_sync_attempts ADD CONSTRAINT fk_source_sync_attempts_source
    FOREIGN KEY (tenant_id, source_id) REFERENCES connector_credential_pairs (tenant_id, id) ON DELETE CASCADE;

CREATE TABLE sharepoint_selection_operations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    source_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    request_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    credential_id UUID,
    credential_revision BIGINT NOT NULL,
    scope_revision BIGINT NOT NULL,
    scope_mode VARCHAR(16) NOT NULL CHECK (scope_mode IN ('ALL_SITES', 'SPECIFIC')),
    source_name VARCHAR(120),
    -- Access and Groups are chosen when a Source is created and applied once verification succeeds.
    access_type VARCHAR(16) CHECK (access_type IS NULL OR access_type IN ('PUBLIC', 'RESTRICTED')),
    group_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    include_documents BOOLEAN NOT NULL DEFAULT TRUE,
    include_pages BOOLEAN NOT NULL DEFAULT FALSE,
    sync_interval_minutes INTEGER NOT NULL,
    prune_interval_hours INTEGER NOT NULL,
    max_requests INTEGER NOT NULL,
    max_roots INTEGER NOT NULL,
    max_request_bytes INTEGER NOT NULL,
    request_count INTEGER NOT NULL DEFAULT 0,
    elapsed_millis BIGINT NOT NULL DEFAULT 0,
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
    UNIQUE (tenant_id, actor_id, request_id),
    FOREIGN KEY (tenant_id, credential_id) REFERENCES credentials(tenant_id, id) ON DELETE SET NULL (credential_id)
);
CREATE UNIQUE INDEX uq_sharepoint_selection_live ON sharepoint_selection_operations (tenant_id, source_id)
    WHERE status IN ('NOT_STARTED', 'IN_PROGRESS');
CREATE INDEX ix_sharepoint_selection_dispatch
    ON sharepoint_selection_operations (status, next_dispatch_at, dispatch_lease_expires_at, created_at);
CREATE INDEX ix_sharepoint_selection_credential ON sharepoint_selection_operations (tenant_id, credential_id);

CREATE TABLE sharepoint_selection_entries (
    tenant_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    position INTEGER NOT NULL CHECK (position >= 0),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('ROOT', 'EXCLUDED_SITE', 'EXCLUDED_PATH')),
    value VARCHAR(2048) NOT NULL,
    root_kind VARCHAR(16) CHECK (root_kind IS NULL OR root_kind IN ('SITE', 'LIBRARY', 'FOLDER')),
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    site_id VARCHAR(512),
    drive_id VARCHAR(512),
    item_id VARCHAR(512),
    display_name VARCHAR(255),
    error_code VARCHAR(64),
    PRIMARY KEY (tenant_id, operation_id, kind, position),
    FOREIGN KEY (tenant_id, operation_id) REFERENCES sharepoint_selection_operations (tenant_id, id) ON DELETE CASCADE
);

CREATE OR REPLACE FUNCTION cancel_sharepoint_selection_deleted_credential() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    UPDATE sharepoint_selection_operations SET status='CANCELLED', error_code='SOURCE_SHAREPOINT_CREDENTIAL_CHANGED',
        completed_at=CURRENT_TIMESTAMP, claim_token=NULL, lease_expires_at=NULL
    WHERE tenant_id=OLD.tenant_id AND credential_id=OLD.id AND status IN ('NOT_STARTED','IN_PROGRESS');
    RETURN OLD;
END $$;
CREATE TRIGGER cancel_sharepoint_selection_deleted_credential BEFORE DELETE ON credentials
    FOR EACH ROW EXECUTE FUNCTION cancel_sharepoint_selection_deleted_credential();

CREATE OR REPLACE FUNCTION fence_sharepoint_selection_credential() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR NEW.credential_revision <> OLD.credential_revision
       OR NEW.connection_status <> 'ACTIVE' THEN
        UPDATE sharepoint_selection_operations SET status='CANCELLED', error_code='SOURCE_SHAREPOINT_CREDENTIAL_CHANGED',
            completed_at=CURRENT_TIMESTAMP, claim_token=NULL, lease_expires_at=NULL
        WHERE tenant_id=OLD.tenant_id AND credential_id=OLD.credential_id AND status IN ('NOT_STARTED','IN_PROGRESS');
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER fence_sharepoint_selection_credential BEFORE DELETE OR UPDATE ON sharepoint_credentials
    FOR EACH ROW EXECUTE FUNCTION fence_sharepoint_selection_credential();

CREATE OR REPLACE FUNCTION cleanup_sharepoint_selection_source() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        DELETE FROM sharepoint_selection_operations WHERE tenant_id=OLD.tenant_id AND source_id=OLD.id;
        RETURN OLD;
    END IF;
    IF NEW.status = 'DELETING' THEN
        UPDATE sharepoint_selection_operations SET status='CANCELLED', error_code='SOURCE_DELETING',
            completed_at=CURRENT_TIMESTAMP, claim_token=NULL, lease_expires_at=NULL
        WHERE tenant_id=OLD.tenant_id AND source_id=OLD.id AND status IN ('NOT_STARTED','IN_PROGRESS');
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER cleanup_sharepoint_selection_source BEFORE DELETE OR UPDATE OF status ON connector_credential_pairs
    FOR EACH ROW EXECUTE FUNCTION cleanup_sharepoint_selection_source();

CREATE OR REPLACE FUNCTION fence_sharepoint_selection_membership() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR NEW.status<>'ACTIVE' THEN
        UPDATE sharepoint_selection_operations SET status='CANCELLED', error_code='IAM_ACCESS_DENIED',
            completed_at=CURRENT_TIMESTAMP, claim_token=NULL, lease_expires_at=NULL
        WHERE tenant_id=OLD.tenant_id AND actor_id=OLD.actor_id AND status IN ('NOT_STARTED','IN_PROGRESS');
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER fence_sharepoint_selection_membership BEFORE DELETE OR UPDATE OF status ON tenant_memberships
    FOR EACH ROW EXECUTE FUNCTION fence_sharepoint_selection_membership();

CREATE OR REPLACE FUNCTION fence_sharepoint_selection_tenant() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status<>'ACTIVE' THEN
        UPDATE sharepoint_selection_operations SET status='CANCELLED', error_code='IAM_ACCESS_DENIED',
            completed_at=CURRENT_TIMESTAMP, claim_token=NULL, lease_expires_at=NULL
        WHERE tenant_id=NEW.id AND status IN ('NOT_STARTED','IN_PROGRESS');
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER fence_sharepoint_selection_tenant BEFORE UPDATE OF status ON tenants
    FOR EACH ROW EXECUTE FUNCTION fence_sharepoint_selection_tenant();
