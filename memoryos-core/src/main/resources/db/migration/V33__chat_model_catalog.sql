ALTER TABLE iam_group_capability_grants DROP CONSTRAINT ck_iam_group_capability_grants_capability;
ALTER TABLE iam_group_capability_grants ADD CONSTRAINT ck_iam_group_capability_grants_capability CHECK (
    capability IN ('IAM_ADMIN', 'USERS_MANAGE', 'GROUPS_READ', 'GROUPS_MANAGE',
                   'SOURCES_READ', 'SOURCES_MANAGE', 'SOURCES_DELETE', 'MODELS_MANAGE')
);

CREATE TABLE llm_provider (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    builtin_key VARCHAR(32),
    name VARCHAR(200) NOT NULL CHECK (length(trim(name)) > 0),
    adapter_type VARCHAR(64) NOT NULL,
    base_url VARCHAR(2048) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    is_public BOOLEAN NOT NULL DEFAULT TRUE,
    credential TEXT,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, builtin_key)
);
CREATE TABLE model_configuration (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    provider_id UUID NOT NULL,
    model_name VARCHAR(200) NOT NULL CHECK (length(trim(model_name)) > 0),
    display_name VARCHAR(200) NOT NULL CHECK (length(trim(display_name)) > 0),
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    settings JSONB NOT NULL CHECK (jsonb_typeof(settings) = 'object'),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    UNIQUE (tenant_id, id),
    UNIQUE (provider_id, model_name),
    FOREIGN KEY (tenant_id, provider_id) REFERENCES llm_provider(tenant_id, id) ON DELETE CASCADE
);
CREATE TABLE chat_model_default (
    tenant_id UUID PRIMARY KEY REFERENCES tenants(id),
    model_configuration_id UUID,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    FOREIGN KEY (tenant_id, model_configuration_id) REFERENCES model_configuration(tenant_id, id)
);
CREATE TABLE llm_provider_group (
    tenant_id UUID NOT NULL,
    provider_id UUID NOT NULL,
    group_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, provider_id, group_id),
    FOREIGN KEY (tenant_id, provider_id) REFERENCES llm_provider(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, group_id) REFERENCES iam_groups(tenant_id, id) ON DELETE CASCADE
);
CREATE TABLE llm_provider_persona (
    tenant_id UUID NOT NULL,
    provider_id UUID NOT NULL,
    persona_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, provider_id, persona_id),
    FOREIGN KEY (tenant_id, provider_id) REFERENCES llm_provider(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, persona_id) REFERENCES persona(tenant_id, id) ON DELETE CASCADE
);
ALTER TABLE persona ADD COLUMN model_configuration_id UUID;
ALTER TABLE persona ADD COLUMN model_revision BIGINT NOT NULL DEFAULT 1 CHECK (model_revision > 0);
ALTER TABLE persona ADD CONSTRAINT fk_persona_model
    FOREIGN KEY (tenant_id, model_configuration_id) REFERENCES model_configuration(tenant_id, id);

-- Historical selection IDs survive catalog deletion. They are metadata, not configuration snapshots.
ALTER TABLE chat_message ADD COLUMN requested_model_configuration_id UUID;
ALTER TABLE chat_message ADD COLUMN selected_model_configuration_id UUID;
ALTER TABLE chat_message ADD COLUMN model_selection_fallback VARCHAR(32);
