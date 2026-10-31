-- MEM-152 phase 4: a Tenant may bound how much a person's file library holds, and raise it for one person.
-- No quota recorded means no limit, which is the behaviour up to now.
ALTER TABLE chat_settings
    ADD COLUMN storage_quota_bytes BIGINT CHECK (storage_quota_bytes IS NULL OR storage_quota_bytes > 0);

CREATE TABLE chat_storage_quota (
    tenant_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    max_bytes BIGINT NOT NULL CHECK (max_bytes > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, actor_id),
    FOREIGN KEY (tenant_id, actor_id) REFERENCES tenant_memberships(tenant_id, actor_id)
);
