-- MEM-246: what a bot receives.

-- The provider organisation the bot's app belongs to, recorded when the app is verified. Until it is known the bot
-- answers nobody: a sender is admitted only when their organisation equals it.
ALTER TABLE channel_connections ADD COLUMN organisation_key VARCHAR(128);

-- Who a provider user is in MemoryOS, remembered for a while so that a message does not ask the provider again.
-- actor_id is null for a member of the organisation who has no account here.
CREATE TABLE channel_identities (
    tenant_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    external_user_id VARCHAR(128) NOT NULL,
    actor_id UUID,
    resolved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (connection_id, external_user_id),
    CONSTRAINT fk_channel_identity_connection FOREIGN KEY (tenant_id, connection_id)
        REFERENCES channel_connections (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_channel_identity_actor FOREIGN KEY (tenant_id, actor_id)
        REFERENCES tenant_memberships (tenant_id, actor_id) ON DELETE CASCADE
);

-- The conversation a direct message continues while it is recent: one a person and chat.
CREATE TABLE channel_conversations (
    tenant_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    external_chat_id VARCHAR(128) NOT NULL,
    external_user_id VARCHAR(128) NOT NULL,
    session_id UUID NOT NULL,
    last_message_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (connection_id, external_chat_id, external_user_id),
    CONSTRAINT fk_channel_conversation_connection FOREIGN KEY (tenant_id, connection_id)
        REFERENCES channel_connections (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_channel_conversation_session FOREIGN KEY (tenant_id, session_id)
        REFERENCES chat_session (tenant_id, id) ON DELETE CASCADE
);

-- One row a message received, by the provider's message id: the first insert wins, so a message pushed again is
-- answered once. It holds ids only; the text of a question and of its answer is in the chat session. A reply to
-- the question or to the bot's answer finds its conversation here.
CREATE TABLE channel_messages (
    tenant_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    external_message_id VARCHAR(128) NOT NULL,
    external_chat_id VARCHAR(128) NOT NULL,
    external_user_id VARCHAR(128) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    session_id UUID,
    assistant_message_id UUID,
    reply_external_message_id VARCHAR(128),
    outcome VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
    reason VARCHAR(64),
    PRIMARY KEY (connection_id, external_message_id),
    CONSTRAINT fk_channel_message_connection FOREIGN KEY (tenant_id, connection_id)
        REFERENCES channel_connections (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_channel_message_session FOREIGN KEY (session_id) REFERENCES chat_session (id) ON DELETE SET NULL,
    CONSTRAINT ck_channel_message_outcome CHECK (outcome IN ('RECEIVED', 'ANSWERED', 'REFUSED', 'FAILED'))
);

CREATE INDEX ix_channel_messages_reply ON channel_messages (connection_id, reply_external_message_id)
    WHERE reply_external_message_id IS NOT NULL;
CREATE INDEX ix_channel_messages_received ON channel_messages (received_at);
