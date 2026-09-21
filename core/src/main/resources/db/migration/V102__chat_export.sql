-- MEM-153 phase 3: taking one's own conversations and files out of MemoryOS. The request is recorded, a Worker
-- packs a ZIP from PostgreSQL and object storage, and the export is a tracked server write released when it
-- expires — the same shape as the MEM-152 library archive, because the mechanics are the same.
CREATE TABLE chat_export (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    owner_actor_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'READY', 'FAILED')),
    -- What the export ended up holding, so the page can say what it took and what it left out.
    session_count INTEGER,
    file_count INTEGER,
    skipped JSONB NOT NULL DEFAULT '[]'::jsonb,
    stored_object_id UUID,
    object_key VARCHAR(240),
    size_bytes BIGINT CHECK (size_bytes IS NULL OR size_bytes > 0),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    lease_until TIMESTAMPTZ,
    failure VARCHAR(200),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    cleanup_token UUID,
    cleanup_until TIMESTAMPTZ,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, owner_actor_id) REFERENCES tenant_memberships(tenant_id, actor_id),
    CHECK ((status = 'READY') = (stored_object_id IS NOT NULL AND object_key IS NOT NULL AND size_bytes IS NOT NULL))
);

CREATE INDEX chat_export_owner ON chat_export(tenant_id, owner_actor_id, created_at DESC, id);
CREATE INDEX chat_export_queue ON chat_export(created_at, id) WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX chat_export_expiry ON chat_export(expires_at) WHERE status = 'READY';
-- One export per person at a time: an export reads everything they own, so a queue of them is a waste.
CREATE UNIQUE INDEX ux_chat_export_active ON chat_export(tenant_id, owner_actor_id)
    WHERE status IN ('PENDING', 'RUNNING');
