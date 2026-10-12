-- MEM-131: Onyx-compatible shared Source collections for agent and Search narrowing.
CREATE TABLE document_set (
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    id UUID NOT NULL,
    owner_actor_id UUID NOT NULL,
    name VARCHAR(200) NOT NULL CHECK (name = trim(name) AND length(name) BETWEEN 1 AND 200 AND name !~ '[[:cntrl:]]'),
    description VARCHAR(2000) NOT NULL DEFAULT '' CHECK (length(description) <= 2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    -- A public set is usable by every Tenant member; it still only narrows their existing Source authority.
    is_public BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, owner_actor_id) REFERENCES tenant_memberships(tenant_id, actor_id)
);
CREATE INDEX ix_document_set_owner ON document_set(tenant_id, owner_actor_id, updated_at DESC, id) WHERE deleted_at IS NULL;
CREATE INDEX ix_document_set_public ON document_set(tenant_id, lower(name), id) WHERE deleted_at IS NULL AND is_public;

CREATE TABLE document_set_source (
    tenant_id UUID NOT NULL,
    document_set_id UUID NOT NULL,
    source_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, document_set_id, source_id),
    FOREIGN KEY (tenant_id, document_set_id) REFERENCES document_set(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, source_id) REFERENCES connector_credential_pairs(tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX ix_document_set_source_source ON document_set_source(tenant_id, source_id, document_set_id);

CREATE TABLE document_set_user_share (
    tenant_id UUID NOT NULL,
    document_set_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, document_set_id, actor_id),
    FOREIGN KEY (tenant_id, document_set_id) REFERENCES document_set(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, actor_id) REFERENCES tenant_memberships(tenant_id, actor_id) ON DELETE CASCADE
);
CREATE INDEX ix_document_set_user_share_actor ON document_set_user_share(tenant_id, actor_id, document_set_id);

CREATE TABLE document_set_group_share (
    tenant_id UUID NOT NULL,
    document_set_id UUID NOT NULL,
    group_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, document_set_id, group_id),
    FOREIGN KEY (tenant_id, document_set_id) REFERENCES document_set(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, group_id) REFERENCES iam_groups(tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX ix_document_set_group_share_group ON document_set_group_share(tenant_id, group_id, document_set_id);

CREATE TABLE persona_document_set (
    tenant_id UUID NOT NULL,
    persona_id UUID NOT NULL,
    document_set_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, persona_id, document_set_id),
    FOREIGN KEY (tenant_id, persona_id) REFERENCES persona(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, document_set_id) REFERENCES document_set(tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX ix_persona_document_set_set ON persona_document_set(tenant_id, document_set_id, persona_id);
