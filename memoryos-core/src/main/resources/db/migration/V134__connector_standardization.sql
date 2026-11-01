-- Connector standardization: what every provider shares is stored once, and each provider keeps a table only for
-- what is its own. Three parts, in one transaction so no trigger ever points at a half-moved schema:
--   1. one selection verification queue (source_selection_operations) with a details table per provider;
--   2. one synchronization state row per Source (source_sync_state);
--   3. the credential revisions on credentials, whose status is the one connection status the engine reads.
-- Every existing row is kept.

-- ---------------------------------------------------------------------------------------------------------------
-- 1. Selection verification queue
-- ---------------------------------------------------------------------------------------------------------------

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM google_drive_selection_operations g JOIN sharepoint_selection_operations s ON s.id = g.id) THEN
        RAISE EXCEPTION 'selection operation ids collide across providers';
    END IF;
END $$;

CREATE TABLE source_selection_operations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    source_id UUID NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    actor_id UUID NOT NULL,
    request_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    credential_id UUID,
    credential_revision BIGINT NOT NULL,
    scope_revision BIGINT NOT NULL,
    source_name VARCHAR(120),
    access_type VARCHAR(16),
    group_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    max_requests INTEGER NOT NULL,
    max_roots INTEGER NOT NULL,
    max_request_bytes INTEGER NOT NULL,
    request_count INTEGER NOT NULL DEFAULT 0,
    elapsed_millis BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
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
    CONSTRAINT uq_source_selection_tenant_id UNIQUE (tenant_id, id),
    -- A request ID is an actor's idempotency key within one provider, as it was with a table per provider.
    CONSTRAINT uq_source_selection_request UNIQUE (tenant_id, actor_id, source_type, request_id),
    CONSTRAINT fk_source_selection_credential FOREIGN KEY (tenant_id, credential_id)
        REFERENCES credentials (tenant_id, id) ON DELETE SET NULL (credential_id),
    CONSTRAINT ck_source_selection_type CHECK (source_type IN ('GOOGLE_DRIVE', 'SHAREPOINT')),
    CONSTRAINT ck_source_selection_status CHECK (status IN
        ('NOT_STARTED', 'IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'SUPERSEDED', 'CANCELLED')),
    CONSTRAINT ck_source_selection_access CHECK (access_type IN ('PUBLIC', 'PRIVATE', 'SYNC')),
    CONSTRAINT ck_source_selection_group_ids CHECK
        (jsonb_typeof(group_ids) = 'array' AND jsonb_array_length(group_ids) <= 100)
);
CREATE UNIQUE INDEX uq_source_selection_live ON source_selection_operations (tenant_id, source_id)
    WHERE status IN ('NOT_STARTED', 'IN_PROGRESS');
CREATE INDEX ix_source_selection_dispatch ON source_selection_operations
    (status, next_dispatch_at, dispatch_lease_expires_at, created_at);
CREATE INDEX ix_source_selection_credential ON source_selection_operations (tenant_id, credential_id);

-- What only Google Drive verifies: the discovery it was submitted against and its metadata checkpoint budget.
CREATE TABLE google_drive_selection_details (
    tenant_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    scope_mode VARCHAR(16) NOT NULL CHECK (scope_mode IN ('GENERAL', 'SPECIFIC')),
    discovery_revision BIGINT NOT NULL,
    max_metadata INTEGER NOT NULL,
    ancestor_count INTEGER NOT NULL DEFAULT 0,
    metadata_count INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, operation_id),
    FOREIGN KEY (tenant_id, operation_id) REFERENCES source_selection_operations (tenant_id, id) ON DELETE CASCADE
);

-- What only SharePoint verifies: the content kinds and schedule the accepted scope asked for.
CREATE TABLE sharepoint_selection_details (
    tenant_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    scope_mode VARCHAR(16) NOT NULL CHECK (scope_mode IN ('ALL_SITES', 'SPECIFIC')),
    include_documents BOOLEAN NOT NULL DEFAULT TRUE,
    include_pages BOOLEAN NOT NULL DEFAULT FALSE,
    sync_interval_minutes INTEGER NOT NULL,
    prune_interval_hours INTEGER NOT NULL,
    PRIMARY KEY (tenant_id, operation_id),
    FOREIGN KEY (tenant_id, operation_id) REFERENCES source_selection_operations (tenant_id, id) ON DELETE CASCADE
);

INSERT INTO source_selection_operations (id, tenant_id, source_id, source_type, actor_id, request_id, request_hash,
    credential_id, credential_revision, scope_revision, source_name, access_type, group_ids, max_requests, max_roots,
    max_request_bytes, request_count, elapsed_millis, status, claim_token, lease_expires_at, delivery_id,
    dispatch_token, dispatch_lease_expires_at, redis_message_id, next_dispatch_at, dispatched_at, dispatch_attempts,
    processing_attempts, failure_attempts, last_transport_error, error_code, origin_trace_id, origin_span_id,
    created_at, started_at, completed_at)
SELECT id, tenant_id, source_id, 'GOOGLE_DRIVE', actor_id, request_id, request_hash,
    credential_id, credential_revision, scope_revision, source_name, access_type, group_ids, max_requests, max_roots,
    max_request_bytes, request_count, elapsed_millis, status, claim_token, lease_expires_at, delivery_id,
    dispatch_token, dispatch_lease_expires_at, redis_message_id, next_dispatch_at, dispatched_at, dispatch_attempts,
    processing_attempts, failure_attempts, last_transport_error, error_code, origin_trace_id, origin_span_id,
    created_at, started_at, completed_at
FROM google_drive_selection_operations
UNION ALL
SELECT id, tenant_id, source_id, 'SHAREPOINT', actor_id, request_id, request_hash,
    credential_id, credential_revision, scope_revision, source_name, access_type, group_ids, max_requests, max_roots,
    max_request_bytes, request_count, elapsed_millis, status, claim_token, lease_expires_at, delivery_id,
    dispatch_token, dispatch_lease_expires_at, redis_message_id, next_dispatch_at, dispatched_at, dispatch_attempts,
    processing_attempts, failure_attempts, last_transport_error, error_code, origin_trace_id, origin_span_id,
    created_at, started_at, completed_at
FROM sharepoint_selection_operations;

INSERT INTO google_drive_selection_details (tenant_id, operation_id, scope_mode, discovery_revision, max_metadata,
    ancestor_count, metadata_count)
SELECT tenant_id, id, scope_mode, discovery_revision, max_metadata, ancestor_count, metadata_count
FROM google_drive_selection_operations;

INSERT INTO sharepoint_selection_details (tenant_id, operation_id, scope_mode, include_documents, include_pages,
    sync_interval_minutes, prune_interval_hours)
SELECT tenant_id, id, scope_mode, include_documents, include_pages, sync_interval_minutes, prune_interval_hours
FROM sharepoint_selection_operations;

-- The checkpoint rows of a verification belong to its provider details, so they cannot attach to another provider's
-- operation.
ALTER TABLE google_drive_selection_entries
    DROP CONSTRAINT google_drive_selection_entries_tenant_id_operation_id_fkey,
    ADD CONSTRAINT fk_google_selection_entry_operation FOREIGN KEY (tenant_id, operation_id)
        REFERENCES google_drive_selection_details (tenant_id, operation_id) ON DELETE CASCADE;
ALTER TABLE google_drive_selection_metadata
    DROP CONSTRAINT google_drive_selection_metadata_tenant_id_operation_id_fkey,
    ADD CONSTRAINT fk_google_selection_metadata_operation FOREIGN KEY (tenant_id, operation_id)
        REFERENCES google_drive_selection_details (tenant_id, operation_id) ON DELETE CASCADE;
ALTER TABLE sharepoint_selection_entries
    DROP CONSTRAINT sharepoint_selection_entries_tenant_id_operation_id_fkey,
    ADD CONSTRAINT fk_sharepoint_selection_entry_operation FOREIGN KEY (tenant_id, operation_id)
        REFERENCES sharepoint_selection_details (tenant_id, operation_id) ON DELETE CASCADE;

DROP TRIGGER cancel_google_selection_deleted_credential ON credentials;
DROP TRIGGER cancel_sharepoint_selection_deleted_credential ON credentials;
DROP TRIGGER cleanup_google_selection_source ON connector_credential_pairs;
DROP TRIGGER cleanup_sharepoint_selection_source ON connector_credential_pairs;
DROP TRIGGER fence_google_selection_credential ON google_drive_credentials;
DROP TRIGGER fence_sharepoint_selection_credential ON sharepoint_credentials;
DROP TRIGGER fence_google_selection_membership ON tenant_memberships;
DROP TRIGGER fence_sharepoint_selection_membership ON tenant_memberships;
DROP TRIGGER fence_google_selection_tenant ON tenants;
DROP TRIGGER fence_sharepoint_selection_tenant ON tenants;
DROP TABLE google_drive_selection_operations;
DROP TABLE sharepoint_selection_operations;
DROP FUNCTION cancel_google_selection_deleted_credential();
DROP FUNCTION cancel_sharepoint_selection_deleted_credential();
DROP FUNCTION cleanup_google_selection_source();
DROP FUNCTION cleanup_sharepoint_selection_source();
DROP FUNCTION fence_google_selection_credential();
DROP FUNCTION fence_sharepoint_selection_credential();
DROP FUNCTION fence_google_selection_membership();
DROP FUNCTION fence_sharepoint_selection_membership();
DROP FUNCTION fence_google_selection_tenant();
DROP FUNCTION fence_sharepoint_selection_tenant();

-- A finished Google Drive verification keeps its verified entries and drops its traversal checkpoint.
CREATE OR REPLACE FUNCTION compact_google_selection_checkpoint() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status NOT IN ('NOT_STARTED', 'IN_PROGRESS') THEN
        DELETE FROM google_drive_selection_ancestors WHERE tenant_id = NEW.tenant_id AND operation_id = NEW.id;
        DELETE FROM google_drive_selection_metadata WHERE tenant_id = NEW.tenant_id AND operation_id = NEW.id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER compact_google_selection_checkpoint AFTER UPDATE OF status ON source_selection_operations
    FOR EACH ROW WHEN (NEW.source_type = 'GOOGLE_DRIVE' AND OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION compact_google_selection_checkpoint();

CREATE FUNCTION cleanup_selection_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        DELETE FROM source_selection_operations WHERE tenant_id = OLD.tenant_id AND source_id = OLD.id;
        RETURN OLD;
    END IF;
    IF NEW.status = 'DELETING' THEN
        UPDATE source_selection_operations SET status = 'CANCELLED', error_code = 'SOURCE_DELETING',
            completed_at = CURRENT_TIMESTAMP, claim_token = NULL, lease_expires_at = NULL
        WHERE tenant_id = OLD.tenant_id AND source_id = OLD.id AND status IN ('NOT_STARTED', 'IN_PROGRESS');
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER cleanup_selection_source BEFORE DELETE OR UPDATE OF status ON connector_credential_pairs
    FOR EACH ROW EXECUTE FUNCTION cleanup_selection_source();

CREATE FUNCTION fence_selection_membership() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR NEW.status <> 'ACTIVE' THEN
        UPDATE source_selection_operations SET status = 'CANCELLED', error_code = 'IAM_ACCESS_DENIED',
            completed_at = CURRENT_TIMESTAMP, claim_token = NULL, lease_expires_at = NULL
        WHERE tenant_id = OLD.tenant_id AND actor_id = OLD.actor_id AND status IN ('NOT_STARTED', 'IN_PROGRESS');
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER fence_selection_membership BEFORE DELETE OR UPDATE OF status ON tenant_memberships
    FOR EACH ROW EXECUTE FUNCTION fence_selection_membership();

CREATE FUNCTION fence_selection_tenant() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status <> 'ACTIVE' THEN
        UPDATE source_selection_operations SET status = 'CANCELLED', error_code = 'IAM_ACCESS_DENIED',
            completed_at = CURRENT_TIMESTAMP, claim_token = NULL, lease_expires_at = NULL
        WHERE tenant_id = NEW.id AND status IN ('NOT_STARTED', 'IN_PROGRESS');
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER fence_selection_tenant BEFORE UPDATE OF status ON tenants
    FOR EACH ROW EXECUTE FUNCTION fence_selection_tenant();

-- ---------------------------------------------------------------------------------------------------------------
-- 2. Synchronization state
-- ---------------------------------------------------------------------------------------------------------------

-- The scope an attempt is fenced against, the generation of its runs and the schedule: one row per synchronized
-- Source, whatever its provider.
CREATE TABLE source_sync_state (
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    scope_revision BIGINT NOT NULL DEFAULT 1 CHECK (scope_revision > 0),
    generation BIGINT NOT NULL DEFAULT 0 CHECK (generation >= 0),
    schedule_revision BIGINT NOT NULL DEFAULT 1 CHECK (schedule_revision > 0),
    sync_interval_minutes INTEGER NOT NULL DEFAULT 30 CHECK (sync_interval_minutes >= 1),
    sync_paused BOOLEAN NOT NULL DEFAULT FALSE,
    next_sync_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_synced_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, source_id),
    FOREIGN KEY (tenant_id, source_id) REFERENCES connector_credential_pairs (tenant_id, id) ON DELETE CASCADE
);

INSERT INTO source_sync_state (tenant_id, source_id, scope_revision, generation, schedule_revision,
    sync_interval_minutes, sync_paused, next_sync_at, last_synced_at)
SELECT tenant_id, source_id, revision, generation, schedule_revision, sync_interval_minutes, sync_paused,
    next_sync_at, last_synced_at
FROM google_drive_sources
UNION ALL
SELECT tenant_id, source_id, scope_revision, generation, schedule_revision, sync_interval_minutes, sync_paused,
    next_sync_at, last_synced_at
FROM sharepoint_sources;

ALTER TABLE google_drive_sources
    DROP COLUMN revision,
    DROP COLUMN generation,
    DROP COLUMN schedule_revision,
    DROP COLUMN sync_interval_minutes,
    DROP COLUMN sync_paused,
    DROP COLUMN next_sync_at,
    DROP COLUMN last_synced_at,
    -- A provider Source row never exists without its synchronization state.
    ADD CONSTRAINT fk_google_drive_source_sync_state FOREIGN KEY (tenant_id, source_id)
        REFERENCES source_sync_state (tenant_id, source_id) ON DELETE CASCADE;
ALTER TABLE sharepoint_sources
    DROP COLUMN scope_revision,
    DROP COLUMN generation,
    DROP COLUMN schedule_revision,
    DROP COLUMN sync_interval_minutes,
    DROP COLUMN sync_paused,
    DROP COLUMN next_sync_at,
    DROP COLUMN last_synced_at,
    ADD CONSTRAINT fk_sharepoint_source_sync_state FOREIGN KEY (tenant_id, source_id)
        REFERENCES source_sync_state (tenant_id, source_id) ON DELETE CASCADE;

-- ---------------------------------------------------------------------------------------------------------------
-- 3. Credential state
-- ---------------------------------------------------------------------------------------------------------------

-- credentials.status is the connection status. Both providers also kept a copy, written in the same transaction;
-- should a row differ, the provider's copy is the one its secret envelope was checked against, so it wins.
UPDATE credentials c SET status = g.connection_status
FROM google_drive_credentials g
WHERE g.tenant_id = c.tenant_id AND g.credential_id = c.id AND c.status <> g.connection_status;
UPDATE credentials c SET status = s.connection_status
FROM sharepoint_credentials s
WHERE s.tenant_id = c.tenant_id AND s.credential_id = c.id AND c.status <> s.connection_status;

ALTER TABLE credentials
    ADD COLUMN credential_revision BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN payload_revision BIGINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_credentials_revision CHECK (credential_revision > 0 AND payload_revision > 0),
    ADD CONSTRAINT uq_credentials_tenant_id_id_status UNIQUE (tenant_id, id, status);

UPDATE credentials c SET credential_revision = g.credential_revision, payload_revision = g.payload_revision
FROM google_drive_credentials g WHERE g.tenant_id = c.tenant_id AND g.credential_id = c.id;
UPDATE credentials c SET credential_revision = s.credential_revision, payload_revision = s.payload_revision
FROM sharepoint_credentials s WHERE s.tenant_id = c.tenant_id AND s.credential_id = c.id;

-- SharePoint's copy of the status guarded nothing and is removed. Google Drive's copy stays because the secret
-- envelope CHECK reads it (a revoked credential keeps no secret), and is now held equal to credentials.status.
ALTER TABLE sharepoint_credentials
    DROP COLUMN connection_status,
    DROP COLUMN credential_revision,
    DROP COLUMN payload_revision;
ALTER TABLE google_drive_credentials
    DROP COLUMN credential_revision,
    DROP COLUMN payload_revision,
    ADD CONSTRAINT fk_google_drive_connection_status FOREIGN KEY (tenant_id, credential_id, connection_status)
        REFERENCES credentials (tenant_id, id, status) DEFERRABLE INITIALLY DEFERRED;

-- A changed, unusable or deleted credential ends the verifications it was accepted for. The codes stay the ones
-- each provider's clients already handle.
CREATE FUNCTION fence_selection_credential() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR NEW.credential_revision <> OLD.credential_revision OR NEW.status <> 'ACTIVE' THEN
        UPDATE source_selection_operations SET status = 'CANCELLED',
            error_code = CASE source_type
                WHEN 'GOOGLE_DRIVE' THEN 'SOURCE_GOOGLE_CREDENTIAL_CHANGED'
                WHEN 'SHAREPOINT' THEN 'SOURCE_SHAREPOINT_CREDENTIAL_CHANGED'
                ELSE 'SOURCE_CREDENTIAL_CHANGED' END,
            completed_at = CURRENT_TIMESTAMP, claim_token = NULL, lease_expires_at = NULL
        WHERE tenant_id = OLD.tenant_id AND credential_id = OLD.id AND status IN ('NOT_STARTED', 'IN_PROGRESS');
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER fence_selection_credential BEFORE DELETE OR UPDATE OF status, credential_revision ON credentials
    FOR EACH ROW EXECUTE FUNCTION fence_selection_credential();
