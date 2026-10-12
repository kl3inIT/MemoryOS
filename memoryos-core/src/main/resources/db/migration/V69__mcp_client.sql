-- MEM-112: Tenant-registered remote MCP servers whose tools Chat can call.

ALTER TABLE iam_group_capability_grants DROP CONSTRAINT ck_iam_group_capability_grants_capability;
ALTER TABLE iam_group_capability_grants ADD CONSTRAINT ck_iam_group_capability_grants_capability CHECK (
    capability IN ('SYSTEM_ADMIN', 'SYSTEM_BASIC', 'USERS_MANAGE', 'GROUPS_MANAGE',
                   'SOURCES_MANAGE', 'MODELS_MANAGE', 'MCP_MANAGE')
);

CREATE TABLE mcp_server (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    slug VARCHAR(16) NOT NULL CHECK (slug ~ '^[a-z0-9]{1,16}$'),
    name VARCHAR(200) NOT NULL CHECK (length(trim(name)) > 0),
    description VARCHAR(2000),
    url VARCHAR(2048) NOT NULL CHECK (length(trim(url)) > 0),
    auth_type VARCHAR(16) NOT NULL CHECK (auth_type IN ('NONE', 'API_TOKEN', 'OAUTH')),
    auth_performer VARCHAR(16) NOT NULL CHECK (auth_performer IN ('ADMIN', 'PER_USER')),
    oauth_provider_mode VARCHAR(16) CHECK (oauth_provider_mode IN ('AUTO_DISCOVERY', 'KNOWN_PROVIDER')),
    oauth_scopes JSONB NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(oauth_scopes) = 'array'),
    oauth_additional_parameters JSONB NOT NULL DEFAULT '{}'
        CHECK (jsonb_typeof(oauth_additional_parameters) = 'object'),
    -- Encrypted header template; admins may type static secrets into header values.
    header_template TEXT,
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('CREATED', 'AWAITING_AUTH', 'FETCHING_TOOLS', 'CONNECTED', 'DISCONNECTED')),
    tenant_wide BOOLEAN NOT NULL DEFAULT TRUE,
    last_refreshed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, slug),
    CONSTRAINT ck_mcp_server_oauth_mode CHECK ((auth_type = 'OAUTH') = (oauth_provider_mode IS NOT NULL)),
    CONSTRAINT ck_mcp_server_none_performer CHECK (auth_type <> 'NONE' OR auth_performer = 'ADMIN')
);

CREATE TABLE mcp_server_group (
    tenant_id UUID NOT NULL,
    server_id UUID NOT NULL,
    group_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, server_id, group_id),
    FOREIGN KEY (tenant_id, server_id) REFERENCES mcp_server(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, group_id) REFERENCES iam_groups(tenant_id, id) ON DELETE CASCADE
);

-- Several clients per server: one per Google Workspace organization (Internal app) or registration.
CREATE TABLE mcp_oauth_client (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    server_id UUID NOT NULL,
    label VARCHAR(100) NOT NULL CHECK (length(trim(label)) > 0),
    source VARCHAR(24) NOT NULL CHECK (source IN ('ADMIN', 'REGISTERED', 'METADATA_DOCUMENT')),
    -- Client credentials are bound to the issuing authorization server (MCP 2026-07-28, SEP-2352).
    issuer VARCHAR(2048) NOT NULL CHECK (length(trim(issuer)) > 0),
    client_id VARCHAR(2048) NOT NULL CHECK (length(trim(client_id)) > 0),
    client_secret TEXT,
    authorization_endpoint VARCHAR(2048) NOT NULL CHECK (length(trim(authorization_endpoint)) > 0),
    token_endpoint VARCHAR(2048) NOT NULL CHECK (length(trim(token_endpoint)) > 0),
    revocation_endpoint VARCHAR(2048),
    registration_client_uri VARCHAR(2048),
    registration_access_token TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, server_id, id),
    UNIQUE (server_id, label),
    FOREIGN KEY (tenant_id, server_id) REFERENCES mcp_server(tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE mcp_server_tool (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    server_id UUID NOT NULL,
    name VARCHAR(128) NOT NULL CHECK (length(name) > 0),
    title VARCHAR(200),
    description TEXT NOT NULL DEFAULT '' CHECK (length(description) <= 16384),
    input_schema JSONB NOT NULL
        CHECK (jsonb_typeof(input_schema) = 'object' AND octet_length(input_schema::text) <= 65536),
    annotations JSONB NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(annotations) = 'object'),
    read_only BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    snapshot_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    UNIQUE (tenant_id, id),
    UNIQUE (server_id, name),
    FOREIGN KEY (tenant_id, server_id) REFERENCES mcp_server(tenant_id, id) ON DELETE CASCADE
);

-- A NULL owner is the shared credential an administrator configured for everyone.
CREATE TABLE mcp_credential (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    server_id UUID NOT NULL,
    owner_actor_id UUID REFERENCES actors(id) ON DELETE CASCADE,
    oauth_client_id UUID,
    payload TEXT NOT NULL CHECK (length(payload) > 0),
    access_expires_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(24) NOT NULL CHECK (status IN ('ACTIVE', 'REAUTH_REQUIRED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, server_id) REFERENCES mcp_server(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, server_id, oauth_client_id)
        REFERENCES mcp_oauth_client(tenant_id, server_id, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uq_mcp_credential_owner ON mcp_credential (server_id, owner_actor_id)
    WHERE owner_actor_id IS NOT NULL;
CREATE UNIQUE INDEX uq_mcp_credential_shared ON mcp_credential (server_id)
    WHERE owner_actor_id IS NULL;
