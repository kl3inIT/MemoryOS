-- MEM-299: a public bot uses only the MCP servers an administrator opened to it. Only a server with a shared
-- credential or none can be opened, since nobody connects as the bot.
ALTER TABLE mcp_server ADD COLUMN public_bots BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE mcp_server ADD CONSTRAINT ck_mcp_server_public_bots_shared
    CHECK (NOT public_bots OR auth_type = 'NONE' OR auth_performer = 'ADMIN');

-- A server open only through chosen assistants (Dust's "Restrict this tool to skills"): whoever may use one of them
-- uses the server through it, and nobody else uses the server at all.
CREATE TABLE mcp_server_persona (
    tenant_id UUID NOT NULL,
    server_id UUID NOT NULL,
    persona_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, server_id, persona_id),
    FOREIGN KEY (tenant_id, server_id) REFERENCES mcp_server (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, persona_id) REFERENCES persona (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_mcp_server_persona_persona ON mcp_server_persona (tenant_id, persona_id);
