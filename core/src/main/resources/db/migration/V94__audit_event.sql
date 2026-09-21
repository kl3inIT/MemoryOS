-- MEM-25 (ADR 0013): server-authored audit evidence. One append-only stream per Tenant, written inside the
-- transaction of the administrative change it records, so a rolled-back change leaves no event behind.
CREATE TABLE audit_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- '<domain>.<verb>', an append-only catalog; a value never changes meaning (Onyx AuditAction).
    action VARCHAR(64) NOT NULL,
    -- The OCSF class the action belongs to, so a SIEM can route without knowing every action.
    event_class VARCHAR(32) NOT NULL CHECK (event_class IN ('AUTHENTICATION', 'ACCOUNT_CHANGE',
        'USER_ACCESS_MANAGEMENT', 'GROUP_MANAGEMENT', 'API_ACTIVITY')),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),
    -- Null for a failed sign-in before the actor is known. Deliberately not a foreign key: an event that can never be
    -- deleted must not keep a person from ever being deleted; the label and e-mail keep the record readable.
    actor_id UUID,
    -- Who the actor was when this happened: a later rename or deletion must not rewrite the record.
    actor_label VARCHAR(320),
    actor_email VARCHAR(320),
    resource_type VARCHAR(32),
    resource_id VARCHAR(200),
    resource_label VARCHAR(320),
    -- Declared fields per action (never free-form): what changed, before and after, with no secret values.
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    trace_id VARCHAR(64),
    endpoint VARCHAR(200),
    source_ip VARCHAR(64),
    schema_version SMALLINT NOT NULL DEFAULT 1,
    CONSTRAINT ck_audit_event_details_object CHECK (jsonb_typeof(details) = 'object')
);

-- The viewer reads newest first within a Tenant and pages by (occurred_at, id); the filters narrow that stream.
CREATE INDEX ix_audit_event_tenant_time ON audit_event (tenant_id, occurred_at DESC, id DESC);
CREATE INDEX ix_audit_event_tenant_actor ON audit_event (tenant_id, actor_id, occurred_at DESC);
CREATE INDEX ix_audit_event_tenant_action ON audit_event (tenant_id, action, occurred_at DESC);

-- Append-only: nothing updates an event, and only the retention sweep deletes one. The sweep announces itself
-- with a transaction-local setting, so an accidental or malicious DELETE elsewhere still fails.
CREATE OR REPLACE FUNCTION audit_event_append_only() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Audit events are append-only';
    END IF;
    IF current_setting('memoryos.audit_retention', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'Audit events are deleted only by the retention sweep';
    END IF;
    RETURN OLD;
END
$$;

CREATE TRIGGER audit_event_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_append_only();

-- Reading the stream is a capability of its own, granted through a Group (ADR 0013).
ALTER TABLE iam_group_capability_grants DROP CONSTRAINT ck_iam_group_capability_grants_capability;
ALTER TABLE iam_group_capability_grants ADD CONSTRAINT ck_iam_group_capability_grants_capability CHECK (
    capability IN ('SYSTEM_ADMIN', 'SYSTEM_BASIC', 'USERS_MANAGE', 'GROUPS_MANAGE',
                   'SOURCES_MANAGE', 'MODELS_MANAGE', 'MCP_MANAGE', 'AGENTS_CREATE', 'AGENTS_MANAGE', 'AUDIT_READ')
);
