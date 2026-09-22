-- MEM-125: who in the organization may read other people's conversations, and how much of them.
-- NORMAL names the asker; ANONYMIZED drops their name and e-mail and nothing else; DISABLED refuses every read.
ALTER TABLE chat_settings ADD COLUMN chat_history_visibility VARCHAR(16) NOT NULL DEFAULT 'NORMAL'
    CHECK (chat_history_visibility IN ('NORMAL', 'ANONYMIZED', 'DISABLED'));

-- Reading the Tenant's conversations is a capability of its own, granted through a Group, as AUDIT_READ is.
ALTER TABLE iam_group_capability_grants DROP CONSTRAINT ck_iam_group_capability_grants_capability;
ALTER TABLE iam_group_capability_grants ADD CONSTRAINT ck_iam_group_capability_grants_capability CHECK (
    capability IN ('SYSTEM_ADMIN', 'SYSTEM_BASIC', 'USERS_MANAGE', 'GROUPS_MANAGE',
                   'SOURCES_MANAGE', 'MODELS_MANAGE', 'MCP_MANAGE', 'AGENTS_CREATE', 'AGENTS_MANAGE', 'AUDIT_READ',
                   'CHAT_HISTORY_READ')
);

-- Every conversation index today is keyed by its owner; an organization-wide read cannot use one.
CREATE INDEX ix_chat_session_tenant_updated ON chat_session (tenant_id, updated_at DESC, id)
    WHERE NOT temporary;
