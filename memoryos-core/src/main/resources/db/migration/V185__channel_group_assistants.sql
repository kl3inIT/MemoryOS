-- MEM-273 step 5: one bot, one assistant per group chat. The bot keeps its default assistant (chat_channel_binding);
-- an administrator may give a group chat the bot answers in an assistant of its own, as Onyx configures a Slack bot by
-- channel under one default.

-- The group chats a bot has answered in, recorded when it accepts a group's first addressed question: the only groups
-- an administrator can choose, since no provider is asked to list them. name is the provider's (Lark gives it, Zalo
-- does not); label is the administrator's own name for the group and is shown first.
CREATE TABLE channel_groups (
    tenant_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    external_chat_id VARCHAR(128) NOT NULL,
    name VARCHAR(200),
    label VARCHAR(100),
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_message_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (connection_id, external_chat_id),
    CONSTRAINT fk_channel_group_connection FOREIGN KEY (tenant_id, connection_id)
        REFERENCES channel_connections (tenant_id, id) ON DELETE CASCADE
);

-- The assistant a group chat answers with in place of its bot's default. Chat's, as the default is: a conversation
-- held in a group may use the assistant only while its bot and group still resolve to it, which Chat checks in its
-- own statements. The channel removes a bot's rows when it removes the bot or forgets the group.
CREATE TABLE chat_channel_group_binding (
    tenant_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    external_chat_id VARCHAR(128) NOT NULL,
    persona_id UUID NOT NULL,
    bound_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (connection_id, external_chat_id),
    FOREIGN KEY (tenant_id, persona_id) REFERENCES persona (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_chat_channel_group_binding_persona ON chat_channel_group_binding (tenant_id, persona_id);

-- The group chat a conversation held in a channel was asked in; null for a direct message.
ALTER TABLE chat_session ADD COLUMN channel_chat_id VARCHAR(128);
