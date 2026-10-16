-- MEM-207: the apps an administrator trusts to connect to the MemoryOS MCP endpoint through a Client ID Metadata
-- Document. Claude and ChatGPT are built in, with their hosts in code; their rows only say whether they are on.
-- Keycloak's memoryos-mcp-cimd profile and policy hold the hosts of the enabled apps of the operating Tenant.
CREATE TABLE mcp_trusted_apps (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    preset VARCHAR(16) NOT NULL CHECK (preset IN ('CLAUDE', 'CHATGPT', 'CUSTOM')),
    name VARCHAR(80) NOT NULL CHECK (length(btrim(name)) > 0),
    client_id_hosts TEXT[] NOT NULL,
    document_hosts TEXT[] NOT NULL,
    enabled BOOLEAN NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (preset <> 'CUSTOM' OR cardinality(client_id_hosts) BETWEEN 1 AND 10)
);
CREATE UNIQUE INDEX mcp_trusted_apps_built_in ON mcp_trusted_apps (tenant_id, preset) WHERE preset <> 'CUSTOM';

-- MEM-209: one row per tool call through the endpoint, as Glean's MCP activity log: who, through which app, which tool
-- and how it ended. No query and no document. Rows older than 90 days are removed by the Worker.
CREATE TABLE mcp_endpoint_calls (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    actor_id UUID NOT NULL,
    client_id VARCHAR(512) NOT NULL,
    client_kind VARCHAR(16) NOT NULL CHECK (client_kind IN ('CLAUDE', 'CHATGPT', 'OTHER')),
    tool VARCHAR(64) NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('SUCCESS', 'REFUSED', 'FAILED', 'RATE_LIMITED')),
    occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX mcp_endpoint_calls_recent ON mcp_endpoint_calls (tenant_id, occurred_at DESC, id DESC);
CREATE INDEX mcp_endpoint_calls_age ON mcp_endpoint_calls (occurred_at);
