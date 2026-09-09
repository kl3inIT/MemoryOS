CREATE TABLE persona (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    builtin_key VARCHAR(32) NOT NULL,
    name VARCHAR(200) NOT NULL,
    instructions TEXT NOT NULL,
    model VARCHAR(200) NOT NULL,
    UNIQUE (tenant_id, builtin_key),
    UNIQUE (tenant_id, id)
);

CREATE TABLE chat_session (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    owner_actor_id UUID NOT NULL,
    persona_id UUID NOT NULL,
    root_message_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL CHECK (length(trim(title)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (tenant_id, owner_actor_id) REFERENCES tenant_memberships(tenant_id, actor_id),
    FOREIGN KEY (tenant_id, persona_id) REFERENCES persona(tenant_id, id)
);
CREATE INDEX ix_chat_session_owner ON chat_session(tenant_id, owner_actor_id, updated_at DESC, id);

CREATE TABLE chat_message (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    parent_message_id UUID,
    latest_child_message_id UUID,
    role VARCHAR(16) NOT NULL CHECK (role IN ('ROOT', 'USER', 'ASSISTANT')),
    content TEXT NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL CHECK (status IN ('COMPLETED', 'RUNNING', 'CANCELED', 'FAILED')),
    client_request_id UUID,
    original_assistant_message_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    deadline_at TIMESTAMPTZ,
    failure_code VARCHAR(64),
    UNIQUE (session_id, id),
    UNIQUE (session_id, parent_message_id, id),
    UNIQUE (session_id, client_request_id),
    FOREIGN KEY (session_id, parent_message_id) REFERENCES chat_message(session_id, id),
    FOREIGN KEY (session_id, id, latest_child_message_id)
        REFERENCES chat_message(session_id, parent_message_id, id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (session_id, id, original_assistant_message_id)
        REFERENCES chat_message(session_id, parent_message_id, id) DEFERRABLE INITIALLY DEFERRED,
    CHECK ((role = 'ROOT' AND parent_message_id IS NULL AND content = '')
        OR (role <> 'ROOT' AND parent_message_id IS NOT NULL)),
    CHECK (parent_message_id IS NULL OR parent_message_id <> id),
    CHECK (role = 'ASSISTANT' OR status = 'COMPLETED'),
    CHECK ((role = 'USER' AND client_request_id IS NOT NULL)
        OR (role <> 'USER' AND client_request_id IS NULL)),
    CHECK ((role = 'USER' AND original_assistant_message_id IS NOT NULL)
        OR (role <> 'USER' AND original_assistant_message_id IS NULL)),
    CHECK ((role = 'ASSISTANT' AND deadline_at IS NOT NULL) OR (role <> 'ASSISTANT' AND deadline_at IS NULL)),
    CHECK ((status = 'RUNNING' AND finished_at IS NULL)
        OR (status <> 'RUNNING' AND finished_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_chat_root ON chat_message(session_id) WHERE role = 'ROOT';
CREATE UNIQUE INDEX uq_chat_active_reply ON chat_message(session_id) WHERE status = 'RUNNING';
CREATE INDEX ix_chat_message_parent ON chat_message(session_id, parent_message_id);
ALTER TABLE chat_session ADD CONSTRAINT fk_chat_root
    FOREIGN KEY (id, root_message_id) REFERENCES chat_message(session_id, id) DEFERRABLE INITIALLY DEFERRED;
