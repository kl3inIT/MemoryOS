-- MEM-273: the assistant a bot answers with. A bot has one assistant and an assistant may have many bots, as LobeHub,
-- GoClaw and OpenClaw bind a channel to one agent; a bot with none, or whose assistant was removed, answers with the
-- Tenant's built-in assistant. The binding is Chat's: a conversation held in a channel may use its bot's assistant
-- only while the bot is still bound to it, which Chat checks in its own statements. The bot is the channel's row;
-- the channel removes its binding when it removes the bot.
CREATE TABLE chat_channel_binding (
    tenant_id UUID NOT NULL,
    connection_id UUID NOT NULL PRIMARY KEY,
    persona_id UUID NOT NULL,
    bound_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (tenant_id, persona_id) REFERENCES persona (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_chat_channel_binding_persona ON chat_channel_binding (tenant_id, persona_id);

-- The bot a conversation held in a channel was asked through, so its binding can be checked for each turn.
ALTER TABLE chat_session ADD COLUMN channel_connection_id UUID;
