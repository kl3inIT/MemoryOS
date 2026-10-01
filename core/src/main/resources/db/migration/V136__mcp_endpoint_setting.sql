-- MEM-114: per-Tenant switch for the MemoryOS MCP endpoint. No row means off.
CREATE TABLE mcp_endpoint_setting (
    tenant_id UUID PRIMARY KEY REFERENCES tenants(id),
    enabled BOOLEAN NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
