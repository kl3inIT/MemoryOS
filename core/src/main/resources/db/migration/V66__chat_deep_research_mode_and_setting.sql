-- Deep research is part of command identity: a replay with a different mode conflicts, as Web intent does.
ALTER TABLE chat_command ADD COLUMN deep_research BOOLEAN NOT NULL DEFAULT FALSE;

-- Tenant Chat settings. As Onyx, Deep research is enabled while no row exists.
CREATE TABLE chat_settings (
    tenant_id UUID PRIMARY KEY REFERENCES tenants(id),
    deep_research_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    revision BIGINT NOT NULL DEFAULT 0
);
