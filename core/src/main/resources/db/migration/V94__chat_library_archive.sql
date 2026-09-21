-- MEM-152 phase 3: downloading a selection as one ZIP. The request is recorded, a Worker packs the files from
-- object storage, and the archive is a tracked server write the owning capability releases when it expires.
CREATE TABLE chat_library_archive (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    owner_actor_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'READY', 'FAILED')),
    -- The files as asked for: [{"source": "UPLOAD", "id": "…"}, …]. Resolved again when packing, so a file
    -- deleted meanwhile is skipped rather than failing the archive.
    requested JSONB NOT NULL,
    file_count INTEGER NOT NULL CHECK (file_count BETWEEN 1 AND 100),
    requested_bytes BIGINT NOT NULL CHECK (requested_bytes > 0),
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

CREATE INDEX chat_library_archive_owner ON chat_library_archive(tenant_id, owner_actor_id, created_at DESC, id);
CREATE INDEX chat_library_archive_queue ON chat_library_archive(created_at, id)
    WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX chat_library_archive_expiry ON chat_library_archive(expires_at) WHERE status = 'READY';
