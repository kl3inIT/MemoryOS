-- MEM-275: MCP Apps (SEP-1865). A tool may name a view, an HTML resource the chat renders with the call's result, and
-- may be visible to the model only or to its view only. Model-only and app-only tools are hidden from the other side.
ALTER TABLE mcp_server_tool
    ADD COLUMN app_resource_uri VARCHAR(1024),
    ADD COLUMN model_visible BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN app_visible BOOLEAN NOT NULL DEFAULT true,
    ADD CONSTRAINT ck_mcp_server_tool_app_resource_uri CHECK (app_resource_uri IS NULL OR app_resource_uri LIKE 'ui://%'),
    ADD CONSTRAINT ck_mcp_server_tool_visibility CHECK (model_visible OR app_visible);

-- The view of one call of an answer: its server, its resource and the call's input and result, read back only to the
-- conversation's owner, and only while they can still use the server. A deleted server takes its views with it.
CREATE TABLE chat_message_tool_apps (
    message_id UUID NOT NULL REFERENCES chat_message (id) ON DELETE CASCADE,
    call_id VARCHAR(256) NOT NULL,
    server_id UUID NOT NULL REFERENCES mcp_server (id) ON DELETE CASCADE,
    resource_uri VARCHAR(1024) NOT NULL,
    input TEXT NOT NULL,
    output TEXT NOT NULL,
    PRIMARY KEY (message_id, call_id),
    CONSTRAINT ck_chat_message_tool_apps_resource_uri CHECK (resource_uri LIKE 'ui://%'),
    CONSTRAINT ck_chat_message_tool_apps_input CHECK (octet_length(input) <= 65536),
    CONSTRAINT ck_chat_message_tool_apps_output CHECK (octet_length(output) <= 65536)
);

CREATE INDEX ix_chat_message_tool_apps_server ON chat_message_tool_apps (server_id);
