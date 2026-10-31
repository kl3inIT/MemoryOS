ALTER TABLE persona ALTER COLUMN builtin_key DROP NOT NULL;
ALTER TABLE persona ADD COLUMN owner_actor_id UUID;
ALTER TABLE persona ADD COLUMN description VARCHAR(2000) NOT NULL DEFAULT '';
ALTER TABLE persona ADD COLUMN search_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE persona ADD COLUMN context_token_limit INTEGER;
ALTER TABLE persona ADD COLUMN output_token_limit INTEGER;
ALTER TABLE persona ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE persona ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE persona ADD CONSTRAINT fk_persona_owner FOREIGN KEY (tenant_id, owner_actor_id)
    REFERENCES tenant_memberships(tenant_id, actor_id);
ALTER TABLE persona ADD CONSTRAINT ck_persona_owner CHECK
    ((builtin_key IS NOT NULL AND builtin_key = 'default' AND owner_actor_id IS NULL) OR (builtin_key IS NULL AND owner_actor_id IS NOT NULL));
ALTER TABLE persona ADD CONSTRAINT ck_persona_editor_bounds CHECK
    (length(trim(name)) > 0 AND length(instructions) <= 32000 AND revision >= 0
     AND (context_token_limit IS NULL OR context_token_limit BETWEEN 256 AND 2000000)
     AND (output_token_limit IS NULL OR output_token_limit BETWEEN 1 AND 200000));
CREATE TABLE persona_starter (
    persona_id UUID NOT NULL REFERENCES persona(id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position BETWEEN 0 AND 7),
    prompt VARCHAR(1000) NOT NULL CHECK (length(trim(prompt)) > 0),
    PRIMARY KEY (persona_id, position)
);
CREATE TABLE persona_source (
    persona_id UUID NOT NULL REFERENCES persona(id) ON DELETE CASCADE,
    source_id UUID NOT NULL,
    PRIMARY KEY (persona_id, source_id)
);

CREATE TABLE chat_project (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    owner_actor_id UUID NOT NULL,
    name VARCHAR(200) NOT NULL CHECK (length(trim(name)) > 0),
    description VARCHAR(2000) NOT NULL DEFAULT '',
    instructions TEXT NOT NULL DEFAULT '' CHECK (length(instructions) <= 32000),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, owner_actor_id, id),
    FOREIGN KEY (tenant_id, owner_actor_id) REFERENCES tenant_memberships(tenant_id, actor_id)
);
CREATE INDEX ix_chat_project_owner ON chat_project(tenant_id, owner_actor_id, updated_at DESC, id);
ALTER TABLE chat_session ADD COLUMN project_id UUID;
ALTER TABLE chat_session ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE chat_session ADD CONSTRAINT fk_chat_project FOREIGN KEY (tenant_id, owner_actor_id, project_id)
    REFERENCES chat_project(tenant_id, owner_actor_id, id);
ALTER TABLE chat_session ADD CONSTRAINT uq_chat_session_tenant UNIQUE (tenant_id, id);

-- Regeneration reserves an assistant without inserting a second user message.
-- A command receipt preserves the original result even after branch/model changes.
CREATE TABLE chat_command (
    session_id UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    operation VARCHAR(16) NOT NULL CHECK (operation IN ('SEND', 'EDIT', 'REGENERATE')),
    target_message_id UUID NOT NULL,
    request_text TEXT NOT NULL CHECK (length(request_text) <= 32000),
    requested_model_id UUID,
    user_message_id UUID NOT NULL,
    assistant_message_id UUID NOT NULL,
    selected_model_id UUID,
    fallback_reason VARCHAR(32),
    PRIMARY KEY (session_id, request_id),
    FOREIGN KEY (session_id, target_message_id) REFERENCES chat_message(session_id, id),
    FOREIGN KEY (session_id, user_message_id) REFERENCES chat_message(session_id, id),
    FOREIGN KEY (session_id, assistant_message_id) REFERENCES chat_message(session_id, id)
);
INSERT INTO chat_command(session_id, request_id, operation, target_message_id, request_text,
    requested_model_id, user_message_id, assistant_message_id, selected_model_id, fallback_reason)
SELECT session_id, client_request_id, 'SEND', parent_message_id, content,
    requested_model_configuration_id, id, original_assistant_message_id,
    selected_model_configuration_id, model_selection_fallback
FROM chat_message WHERE role = 'USER';

CREATE TABLE chat_sharing (
    session_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    FOREIGN KEY (tenant_id, session_id) REFERENCES chat_session(tenant_id, id) ON DELETE CASCADE
);
CREATE TABLE chat_feedback (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    session_id UUID NOT NULL,
    assistant_message_id UUID NOT NULL,
    positive BOOLEAN,
    comment VARCHAR(4000) NOT NULL DEFAULT '',
    reason VARCHAR(100) NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    UNIQUE (actor_id, assistant_message_id),
    FOREIGN KEY (tenant_id, actor_id) REFERENCES tenant_memberships(tenant_id, actor_id),
    FOREIGN KEY (tenant_id, session_id) REFERENCES chat_session(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (session_id, assistant_message_id) REFERENCES chat_message(session_id, id),
    CHECK (positive IS NOT NULL OR length(trim(comment)) > 0)
);
