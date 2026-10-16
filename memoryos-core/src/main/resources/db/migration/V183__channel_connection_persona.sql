-- MEM-273: the assistant a bot answers with. A bot has one assistant and an assistant may have many bots, as LobeHub,
-- GoClaw and OpenClaw bind a channel to one agent; a bot with none, or whose assistant was removed, answers with the
-- Tenant's built-in assistant. Removing the assistant row clears only this column.
ALTER TABLE channel_connections ADD COLUMN persona_id UUID;

ALTER TABLE channel_connections
    ADD CONSTRAINT fk_channel_connections_persona FOREIGN KEY (tenant_id, persona_id)
        REFERENCES persona (tenant_id, id) ON DELETE SET NULL (persona_id);

CREATE INDEX ix_channel_connections_persona ON channel_connections (tenant_id, persona_id) WHERE persona_id IS NOT NULL;
