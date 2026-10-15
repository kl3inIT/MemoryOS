-- MEM-270: the sandbox each Chat conversation works in. One row a conversation, kept while the conversation exists;
-- the runtime's sandbox comes and goes (idle expiry, a four-hour life), and the row names the one alive, if any.
CREATE TABLE sandbox_workspaces (
    tenant_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    -- Whose authority the sandbox works under: the conversation's owner.
    actor_id UUID NOT NULL,
    -- The runtime that holds runtime_id; a second runtime adds a value.
    runtime VARCHAR(16) NOT NULL,
    runtime_id VARCHAR(255),
    -- When runtime_id was created, which bounds its total life.
    started_at TIMESTAMP WITH TIME ZONE,
    -- A start in progress: the place is taken in the running bound while the runtime creates the sandbox outside any
    -- transaction, and the token lets only that start record its result.
    starting_since TIMESTAMP WITH TIME ZONE,
    starting_token UUID,
    last_used_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, conversation_id),
    CONSTRAINT fk_sandbox_workspace_conversation FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES chat_session (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_sandbox_workspace_actor FOREIGN KEY (tenant_id, actor_id)
        REFERENCES tenant_memberships (tenant_id, actor_id) ON DELETE CASCADE,
    CONSTRAINT ck_sandbox_workspace_runtime CHECK (runtime IN ('OPENSANDBOX')),
    CONSTRAINT ck_sandbox_workspace_started CHECK ((runtime_id IS NULL) = (started_at IS NULL)),
    CONSTRAINT ck_sandbox_workspace_starting CHECK ((starting_since IS NULL) = (starting_token IS NULL))
);
-- The running bound counts these; the Worker's reconciliation reads them.
CREATE INDEX ix_sandbox_workspaces_running ON sandbox_workspaces (runtime, runtime_id)
    WHERE runtime_id IS NOT NULL OR starting_since IS NOT NULL;
