CREATE TABLE organizations (
    id UUID PRIMARY KEY,
    slug VARCHAR(63) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL,
    default_workspace_id UUID,
    bootstrap_reference VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_organizations_slug UNIQUE (slug),
    CONSTRAINT ck_organizations_status CHECK (status = 'ACTIVE' OR status = 'INACTIVE')
);

CREATE TABLE organization_bootstrap_state (
    id SMALLINT PRIMARY KEY,
    initial_organization_id UUID,
    CONSTRAINT ck_organization_bootstrap_state_singleton CHECK (id = 1),
    CONSTRAINT uq_organization_bootstrap_state_organization UNIQUE (initial_organization_id),
    CONSTRAINT fk_organization_bootstrap_state_organization
        FOREIGN KEY (initial_organization_id) REFERENCES organizations (id) ON DELETE RESTRICT
);

INSERT INTO organization_bootstrap_state (id, initial_organization_id) VALUES (1, NULL);

CREATE TABLE workspaces (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    slug VARCHAR(63) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_workspaces_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT uq_workspaces_organization_slug UNIQUE (organization_id, slug),
    CONSTRAINT fk_workspaces_organization
        FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE RESTRICT,
    CONSTRAINT ck_workspaces_status CHECK (status = 'ACTIVE' OR status = 'RETIRED')
);

ALTER TABLE organizations
    ADD CONSTRAINT fk_organizations_default_workspace
        FOREIGN KEY (id, default_workspace_id)
        REFERENCES workspaces (organization_id, id)
        ON DELETE RESTRICT;

CREATE TABLE organization_memberships (
    organization_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    role VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_organization_memberships PRIMARY KEY (organization_id, actor_id),
    CONSTRAINT fk_organization_memberships_organization
        FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE RESTRICT,
    CONSTRAINT fk_organization_memberships_actor
        FOREIGN KEY (actor_id) REFERENCES actors (id) ON DELETE RESTRICT,
    CONSTRAINT ck_organization_memberships_role CHECK (
        role = 'OWNER' OR role = 'ADMIN' OR role = 'MEMBER'
    ),
    CONSTRAINT ck_organization_memberships_status CHECK (status = 'ACTIVE' OR status = 'INACTIVE')
);

CREATE INDEX ix_organization_memberships_actor
    ON organization_memberships (actor_id, status);

CREATE TABLE workspace_memberships (
    organization_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    role VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_workspace_memberships PRIMARY KEY (organization_id, workspace_id, actor_id),
    CONSTRAINT fk_workspace_memberships_workspace
        FOREIGN KEY (organization_id, workspace_id)
        REFERENCES workspaces (organization_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_workspace_memberships_organization_actor
        FOREIGN KEY (organization_id, actor_id)
        REFERENCES organization_memberships (organization_id, actor_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_workspace_memberships_role CHECK (role = 'ADMIN' OR role = 'MEMBER'),
    CONSTRAINT ck_workspace_memberships_status CHECK (status = 'ACTIVE' OR status = 'INACTIVE')
);

CREATE INDEX ix_workspace_memberships_actor
    ON workspace_memberships (actor_id, status);

CREATE TABLE spring_session (
    primary_id CHAR(36) NOT NULL,
    session_id CHAR(36) NOT NULL,
    creation_time BIGINT NOT NULL,
    last_access_time BIGINT NOT NULL,
    max_inactive_interval INTEGER NOT NULL,
    expiry_time BIGINT NOT NULL,
    principal_name VARCHAR(100),
    CONSTRAINT pk_spring_session PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX uq_spring_session_session_id ON spring_session (session_id);
CREATE INDEX ix_spring_session_expiry_time ON spring_session (expiry_time);
CREATE INDEX ix_spring_session_principal_name ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL,
    attribute_name VARCHAR(200) NOT NULL,
    attribute_bytes BYTEA NOT NULL,
    CONSTRAINT pk_spring_session_attributes PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT fk_spring_session_attributes_session
        FOREIGN KEY (session_primary_id) REFERENCES spring_session (primary_id) ON DELETE CASCADE
);