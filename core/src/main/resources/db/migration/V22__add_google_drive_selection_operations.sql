CREATE TABLE google_drive_selection_operations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    source_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    request_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    credential_id UUID,
    credential_revision BIGINT NOT NULL,
    scope_revision BIGINT NOT NULL,
    discovery_revision BIGINT NOT NULL,
    scope_mode VARCHAR(16) NOT NULL CHECK (scope_mode IN ('GENERAL','SPECIFIC')),
    source_name VARCHAR(120),
    max_requests INTEGER NOT NULL,
    max_metadata INTEGER NOT NULL,
    max_roots INTEGER NOT NULL,
    max_request_bytes INTEGER NOT NULL,
    request_count INTEGER NOT NULL DEFAULT 0,
    elapsed_millis BIGINT NOT NULL DEFAULT 0,
    ancestor_count INTEGER NOT NULL DEFAULT 0,
    metadata_count INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED'
        CHECK (status IN ('NOT_STARTED','IN_PROGRESS','SUCCEEDED','FAILED','SUPERSEDED','CANCELLED')),
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
    UNIQUE (tenant_id,id),
    UNIQUE (tenant_id,actor_id,request_id),
    FOREIGN KEY (tenant_id,credential_id) REFERENCES credentials(tenant_id,id) ON DELETE SET NULL (credential_id)
);
CREATE UNIQUE INDEX uq_google_selection_live ON google_drive_selection_operations(tenant_id,source_id)
    WHERE status IN ('NOT_STARTED','IN_PROGRESS');
CREATE INDEX ix_google_selection_dispatch ON google_drive_selection_operations(status,next_dispatch_at,dispatch_lease_expires_at,created_at);
CREATE INDEX ix_google_selection_credential ON google_drive_selection_operations(tenant_id,credential_id);
CREATE TABLE google_drive_selection_entries (
    tenant_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    file_id VARCHAR(256) NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('ROOT','APPROVAL')),
    was_selected BOOLEAN NOT NULL DEFAULT FALSE,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    covered BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
    name VARCHAR(255),
    mime_type VARCHAR(160),
    PRIMARY KEY(tenant_id,operation_id,file_id,kind),
    FOREIGN KEY(tenant_id,operation_id) REFERENCES google_drive_selection_operations(tenant_id,id) ON DELETE CASCADE
);
CREATE TABLE google_drive_selection_metadata (
    tenant_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    lookup_id VARCHAR(256) NOT NULL,
    file_id VARCHAR(256) NOT NULL,
    name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(160) NOT NULL,
    version VARCHAR(512) NOT NULL,
    trashed BOOLEAN NOT NULL,
    drive_id VARCHAR(256),
    shortcut_target_id VARCHAR(256),
    parents TEXT[] NOT NULL,
    reaches_root BOOLEAN,
    PRIMARY KEY(tenant_id,operation_id,lookup_id),
    FOREIGN KEY(tenant_id,operation_id) REFERENCES google_drive_selection_operations(tenant_id,id) ON DELETE CASCADE
);
CREATE TABLE google_drive_selection_ancestors (
    tenant_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    file_id VARCHAR(256) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    ancestor_id VARCHAR(256) NOT NULL,
    visited BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY(tenant_id,operation_id,file_id,kind,ancestor_id),
    FOREIGN KEY(tenant_id,operation_id,file_id,kind)
        REFERENCES google_drive_selection_entries(tenant_id,operation_id,file_id,kind) ON DELETE CASCADE
);
CREATE INDEX ix_google_selection_ancestor_pending
    ON google_drive_selection_ancestors(tenant_id,operation_id,file_id,kind,ancestor_id) WHERE NOT visited;
CREATE OR REPLACE FUNCTION cancel_google_selection_deleted_credential() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    UPDATE google_drive_selection_operations SET status='CANCELLED',error_code='SOURCE_GOOGLE_CREDENTIAL_CHANGED',
        completed_at=CURRENT_TIMESTAMP,claim_token=NULL,lease_expires_at=NULL
    WHERE tenant_id=OLD.tenant_id AND credential_id=OLD.id AND status IN ('NOT_STARTED','IN_PROGRESS');
    RETURN OLD;
END $$;
CREATE TRIGGER cancel_google_selection_deleted_credential BEFORE DELETE ON credentials
    FOR EACH ROW EXECUTE FUNCTION cancel_google_selection_deleted_credential();
CREATE OR REPLACE FUNCTION fence_google_selection_credential() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR NEW.credential_revision <> OLD.credential_revision
       OR NEW.connection_status <> 'ACTIVE' THEN
        UPDATE google_drive_selection_operations SET status='CANCELLED', error_code='SOURCE_GOOGLE_CREDENTIAL_CHANGED',
            completed_at=CURRENT_TIMESTAMP, claim_token=NULL, lease_expires_at=NULL
        WHERE tenant_id=OLD.tenant_id AND credential_id=OLD.credential_id AND status IN ('NOT_STARTED','IN_PROGRESS');
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER fence_google_selection_credential BEFORE DELETE OR UPDATE ON google_drive_credentials
    FOR EACH ROW EXECUTE FUNCTION fence_google_selection_credential();
CREATE OR REPLACE FUNCTION cleanup_google_selection_source() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        DELETE FROM google_drive_selection_operations WHERE tenant_id=OLD.tenant_id AND source_id=OLD.id;
        RETURN OLD;
    END IF;
    IF NEW.status = 'DELETING' THEN
        UPDATE google_drive_selection_operations SET status='CANCELLED',error_code='SOURCE_DELETING',
            completed_at=CURRENT_TIMESTAMP,claim_token=NULL,lease_expires_at=NULL
        WHERE tenant_id=OLD.tenant_id AND source_id=OLD.id AND status IN ('NOT_STARTED','IN_PROGRESS');
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER cleanup_google_selection_source BEFORE DELETE OR UPDATE OF status ON connector_credential_pairs
    FOR EACH ROW EXECUTE FUNCTION cleanup_google_selection_source();
CREATE INDEX ix_google_roots_page ON google_drive_roots(tenant_id,source_id,name,file_id);
CREATE INDEX ix_google_linked_page ON google_drive_linked_documents(tenant_id,source_id,name,file_id);

CREATE OR REPLACE FUNCTION fence_google_selection_owner() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR NEW.role<>'OWNER' OR NEW.status<>'ACTIVE' THEN
        UPDATE google_drive_selection_operations SET status='CANCELLED',error_code='SOURCE_NOT_OWNER',
            completed_at=CURRENT_TIMESTAMP,claim_token=NULL,lease_expires_at=NULL
        WHERE tenant_id=OLD.tenant_id AND actor_id=OLD.actor_id AND status IN ('NOT_STARTED','IN_PROGRESS');
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER fence_google_selection_owner BEFORE DELETE OR UPDATE OF role,status ON tenant_memberships
    FOR EACH ROW EXECUTE FUNCTION fence_google_selection_owner();
CREATE OR REPLACE FUNCTION fence_google_selection_tenant() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status<>'ACTIVE' THEN
        UPDATE google_drive_selection_operations SET status='CANCELLED',error_code='SOURCE_NOT_OWNER',
            completed_at=CURRENT_TIMESTAMP,claim_token=NULL,lease_expires_at=NULL
        WHERE tenant_id=NEW.id AND status IN ('NOT_STARTED','IN_PROGRESS');
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER fence_google_selection_tenant BEFORE UPDATE OF status ON tenants
    FOR EACH ROW EXECUTE FUNCTION fence_google_selection_tenant();
CREATE OR REPLACE FUNCTION compact_google_selection_checkpoint() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status NOT IN ('NOT_STARTED','IN_PROGRESS') THEN
        DELETE FROM google_drive_selection_ancestors WHERE tenant_id=NEW.tenant_id AND operation_id=NEW.id;
        DELETE FROM google_drive_selection_metadata WHERE tenant_id=NEW.tenant_id AND operation_id=NEW.id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER compact_google_selection_checkpoint AFTER UPDATE OF status ON google_drive_selection_operations
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status) EXECUTE FUNCTION compact_google_selection_checkpoint();
