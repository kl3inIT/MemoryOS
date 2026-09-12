-- A distinct consumer keeps Source FILE's 10 MiB admission and storage boundary intact.
ALTER TABLE stored_objects DROP CONSTRAINT ck_stored_objects_size;
ALTER TABLE stored_objects ADD CONSTRAINT ck_stored_objects_size CHECK (
    (input_kind = 'BINARY' AND size_bytes BETWEEN 1 AND 10485760)
    OR (input_kind = 'NATIVE_SNAPSHOT' AND size_bytes BETWEEN 1 AND 33554432)
    OR (input_kind = 'CHAT_FILE' AND size_bytes BETWEEN 1 AND 262144000)
);
ALTER TABLE object_uploads DROP CONSTRAINT ck_object_uploads_input_kind;
ALTER TABLE object_uploads ADD CONSTRAINT ck_object_uploads_input_kind CHECK (input_kind IN ('BINARY', 'CHAT_FILE'));

CREATE TABLE chat_user_file (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    owner_actor_id UUID NOT NULL,
    request_id UUID NOT NULL,
    upload_id UUID NOT NULL,
    filename VARCHAR(255) NOT NULL CHECK (length(trim(filename)) > 0),
    media_type VARCHAR(160) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes BETWEEN 1 AND 262144000),
    content_sha256 VARCHAR(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    status VARCHAR(24) NOT NULL DEFAULT 'UPLOADING'
        CHECK (status IN ('UPLOADING', 'PROCESSING', 'READY', 'FAILED', 'DELETING', 'DELETED')),
    document_id UUID,
    plaintext TEXT CHECK (length(plaintext) <= 2000000),
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, owner_actor_id, request_id),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, upload_id),
    FOREIGN KEY (tenant_id, owner_actor_id) REFERENCES tenant_memberships(tenant_id, actor_id),
    FOREIGN KEY (tenant_id, upload_id) REFERENCES object_uploads(tenant_id, id),
    FOREIGN KEY (tenant_id, document_id) REFERENCES documents(tenant_id, id)
);
CREATE INDEX ix_chat_user_file_owner ON chat_user_file(tenant_id, owner_actor_id, created_at DESC, id);

CREATE TABLE chat_file_work (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    file_id UUID NOT NULL,
    action VARCHAR(16) NOT NULL CHECK (action IN ('PROCESS','DELETE')),
    status VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED'
        CHECK (status IN ('NOT_STARTED','IN_PROGRESS','COMPLETED','FAILED','CANCELLED')),
    claim_token UUID,
    lease_expires_at TIMESTAMPTZ,
    processing_attempts INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(64),
    delivery_id UUID,
    dispatch_token UUID,
    dispatch_lease_expires_at TIMESTAMPTZ,
    dispatch_attempts INTEGER NOT NULL DEFAULT 0,
    next_dispatch_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    redis_message_id VARCHAR(128),
    dispatched_at TIMESTAMPTZ,
    last_transport_error VARCHAR(64),
    origin_trace_id VARCHAR(32),
    origin_span_id VARCHAR(16),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    FOREIGN KEY (tenant_id,file_id) REFERENCES chat_user_file(tenant_id,id),
    CHECK ((status='IN_PROGRESS') = (claim_token IS NOT NULL AND lease_expires_at IS NOT NULL))
);
CREATE UNIQUE INDEX ix_chat_file_work_active ON chat_file_work(tenant_id,file_id,action)
    WHERE status IN ('NOT_STARTED','IN_PROGRESS');
CREATE INDEX ix_chat_file_work_dispatch ON chat_file_work(next_dispatch_at,created_at,id)
    WHERE status IN ('NOT_STARTED','IN_PROGRESS');
