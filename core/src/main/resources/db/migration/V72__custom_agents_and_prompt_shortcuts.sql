-- MEM-119: Onyx-equivalent custom agents (Persona sharing, discovery, tools, ownership) and prompt shortcuts.

ALTER TABLE iam_group_capability_grants DROP CONSTRAINT ck_iam_group_capability_grants_capability;
ALTER TABLE iam_group_capability_grants ADD CONSTRAINT ck_iam_group_capability_grants_capability CHECK (
    capability IN ('SYSTEM_ADMIN', 'SYSTEM_BASIC', 'USERS_MANAGE', 'GROUPS_MANAGE',
                   'SOURCES_MANAGE', 'MODELS_MANAGE', 'MCP_MANAGE', 'AGENTS_CREATE', 'AGENTS_MANAGE')
);

ALTER TABLE persona ADD COLUMN owner_group_id UUID;
ALTER TABLE persona ADD COLUMN is_public BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE persona ADD COLUMN public_permission VARCHAR(8) NOT NULL DEFAULT 'VIEWER'
    CHECK (public_permission IN ('VIEWER', 'EDITOR'));
ALTER TABLE persona ADD COLUMN is_listed BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE persona ADD COLUMN is_featured BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE persona ADD COLUMN display_priority INTEGER CHECK (display_priority IS NULL OR display_priority BETWEEN 0 AND 100000);
ALTER TABLE persona ADD COLUMN icon_name VARCHAR(40) CHECK (icon_name IS NULL OR icon_name ~ '^[a-z0-9-]{1,40}$');
ALTER TABLE persona ADD COLUMN avatar_file_id UUID;
ALTER TABLE persona ADD COLUMN task_prompt TEXT NOT NULL DEFAULT '' CHECK (length(task_prompt) <= 32000);
ALTER TABLE persona ADD COLUMN replace_base_system_prompt BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE persona ADD COLUMN datetime_aware BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE persona ADD COLUMN knowledge_cutoff TIMESTAMP WITH TIME ZONE;
ALTER TABLE persona ADD COLUMN created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE persona ADD CONSTRAINT fk_persona_owner_group FOREIGN KEY (tenant_id, owner_group_id)
    REFERENCES iam_groups (tenant_id, id) ON DELETE SET NULL (owner_group_id);
ALTER TABLE persona ADD CONSTRAINT fk_persona_avatar_file FOREIGN KEY (avatar_file_id)
    REFERENCES chat_user_file (id) ON DELETE SET NULL;
ALTER TABLE persona DROP CONSTRAINT ck_persona_owner;
-- A custom agent is vacant when both owners are absent (owner Group deleted); Actor ownership moves only by transfer.
ALTER TABLE persona ADD CONSTRAINT ck_persona_owner CHECK (
    (builtin_key = 'default' AND owner_actor_id IS NULL AND owner_group_id IS NULL)
    OR (builtin_key IS NULL AND NOT (owner_actor_id IS NOT NULL AND owner_group_id IS NOT NULL))
);
CREATE INDEX ix_persona_owner_actor ON persona (tenant_id, owner_actor_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_persona_public_listed ON persona (tenant_id, is_public, is_listed) WHERE deleted_at IS NULL;

CREATE TABLE persona_user_share (
    tenant_id UUID NOT NULL,
    persona_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    permission VARCHAR(8) NOT NULL CHECK (permission IN ('VIEWER', 'EDITOR')),
    PRIMARY KEY (tenant_id, persona_id, actor_id),
    FOREIGN KEY (tenant_id, persona_id) REFERENCES persona (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, actor_id) REFERENCES tenant_memberships (tenant_id, actor_id) ON DELETE CASCADE
);
CREATE INDEX ix_persona_user_share_actor ON persona_user_share (tenant_id, actor_id, persona_id);

CREATE TABLE persona_group_share (
    tenant_id UUID NOT NULL,
    persona_id UUID NOT NULL,
    group_id UUID NOT NULL,
    permission VARCHAR(8) NOT NULL CHECK (permission IN ('VIEWER', 'EDITOR')),
    PRIMARY KEY (tenant_id, persona_id, group_id),
    FOREIGN KEY (tenant_id, persona_id) REFERENCES persona (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, group_id) REFERENCES iam_groups (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX ix_persona_group_share_group ON persona_group_share (tenant_id, group_id, persona_id);

CREATE TABLE persona_label (
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    id UUID NOT NULL,
    name VARCHAR(100) NOT NULL CHECK (name = trim(name) AND length(name) BETWEEN 1 AND 100),
    PRIMARY KEY (tenant_id, id)
);
CREATE UNIQUE INDEX uq_persona_label_name ON persona_label (tenant_id, lower(name));

CREATE TABLE persona_label_assignment (
    tenant_id UUID NOT NULL,
    persona_id UUID NOT NULL,
    label_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, persona_id, label_id),
    FOREIGN KEY (tenant_id, persona_id) REFERENCES persona (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, label_id) REFERENCES persona_label (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE persona_tool (
    tenant_id UUID NOT NULL,
    persona_id UUID NOT NULL,
    tool_key VARCHAR(32) NOT NULL CHECK (tool_key IN ('search', 'web_search', 'image_generation')),
    PRIMARY KEY (tenant_id, persona_id, tool_key),
    FOREIGN KEY (tenant_id, persona_id) REFERENCES persona (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE persona_mcp_server (
    tenant_id UUID NOT NULL,
    persona_id UUID NOT NULL,
    server_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, persona_id, server_id),
    FOREIGN KEY (tenant_id, persona_id) REFERENCES persona (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, server_id) REFERENCES mcp_server (tenant_id, id) ON DELETE CASCADE
);

-- Existing agents keep their behavior: internal search follows the old switch, other tools stay available.
INSERT INTO persona_tool (tenant_id, persona_id, tool_key)
SELECT tenant_id, id, 'search' FROM persona WHERE search_enabled;
INSERT INTO persona_tool (tenant_id, persona_id, tool_key)
SELECT tenant_id, id, tool FROM persona CROSS JOIN (VALUES ('web_search'), ('image_generation')) AS tools (tool);
INSERT INTO persona_mcp_server (tenant_id, persona_id, server_id)
SELECT p.tenant_id, p.id, s.id FROM persona p JOIN mcp_server s ON s.tenant_id = p.tenant_id
WHERE p.builtin_key IS NULL;
ALTER TABLE persona DROP COLUMN search_enabled;

CREATE TABLE actor_pinned_persona (
    tenant_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    persona_id UUID NOT NULL,
    position INTEGER NOT NULL CHECK (position BETWEEN 0 AND 99),
    PRIMARY KEY (tenant_id, actor_id, persona_id),
    UNIQUE (tenant_id, actor_id, position),
    FOREIGN KEY (tenant_id, actor_id) REFERENCES tenant_memberships (tenant_id, actor_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, persona_id) REFERENCES persona (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE actor_agent_preferences (
    tenant_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    pins_seeded BOOLEAN NOT NULL DEFAULT FALSE,
    shortcuts_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    revision BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, actor_id),
    FOREIGN KEY (tenant_id, actor_id) REFERENCES tenant_memberships (tenant_id, actor_id) ON DELETE CASCADE
);

CREATE TABLE prompt_shortcut (
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    id UUID NOT NULL,
    owner_actor_id UUID,
    name VARCHAR(100) NOT NULL CHECK (name = trim(name) AND length(name) BETWEEN 1 AND 100 AND name !~ '[[:cntrl:]]'),
    content TEXT NOT NULL CHECK (length(trim(content)) > 0 AND length(content) <= 8000),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revision BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, owner_actor_id) REFERENCES tenant_memberships (tenant_id, actor_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uq_prompt_shortcut_owner_name ON prompt_shortcut (tenant_id, owner_actor_id, lower(name))
    WHERE owner_actor_id IS NOT NULL;
CREATE UNIQUE INDEX uq_prompt_shortcut_public_name ON prompt_shortcut (tenant_id, lower(name))
    WHERE owner_actor_id IS NULL;

CREATE TABLE prompt_shortcut_hidden (
    tenant_id UUID NOT NULL,
    shortcut_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, shortcut_id, actor_id),
    FOREIGN KEY (tenant_id, shortcut_id) REFERENCES prompt_shortcut (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, actor_id) REFERENCES tenant_memberships (tenant_id, actor_id) ON DELETE CASCADE
);

UPDATE tenants SET authorization_version = authorization_version + 1;
