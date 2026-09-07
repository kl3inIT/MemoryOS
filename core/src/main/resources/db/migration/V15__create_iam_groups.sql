CREATE TABLE iam_groups (
    tenant_id UUID NOT NULL,
    id UUID NOT NULL,
    name VARCHAR(200) NOT NULL,
    system_key VARCHAR(16),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_iam_groups PRIMARY KEY (tenant_id, id),
    CONSTRAINT fk_iam_groups_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT uq_iam_groups_tenant_system_key UNIQUE (tenant_id, system_key),
    CONSTRAINT ck_iam_groups_name CHECK (
        name = TRIM(name) AND CHAR_LENGTH(name) BETWEEN 1 AND 200
    ),
    CONSTRAINT ck_iam_groups_system_key CHECK (
        system_key IS NULL OR system_key IN ('ADMIN', 'BASIC')
    )
);

CREATE UNIQUE INDEX uq_iam_groups_tenant_lower_name
    ON iam_groups (tenant_id, LOWER(name));
CREATE INDEX ix_iam_groups_tenant_name
    ON iam_groups (tenant_id, name, id);

CREATE TABLE iam_group_memberships (
    tenant_id UUID NOT NULL,
    group_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    is_manager BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_iam_group_memberships PRIMARY KEY (tenant_id, group_id, actor_id),
    CONSTRAINT fk_iam_group_memberships_group
        FOREIGN KEY (tenant_id, group_id)
        REFERENCES iam_groups (tenant_id, id)
        ON DELETE CASCADE,
    CONSTRAINT fk_iam_group_memberships_tenant_membership
        FOREIGN KEY (tenant_id, actor_id)
        REFERENCES tenant_memberships (tenant_id, actor_id)
        ON DELETE CASCADE
);

CREATE INDEX ix_iam_group_memberships_actor
    ON iam_group_memberships (tenant_id, actor_id, is_manager, group_id);
CREATE INDEX ix_iam_group_memberships_group_manager
    ON iam_group_memberships (tenant_id, group_id, is_manager, actor_id);

CREATE TABLE iam_group_capability_grants (
    tenant_id UUID NOT NULL,
    group_id UUID NOT NULL,
    capability VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_iam_group_capability_grants PRIMARY KEY (tenant_id, group_id, capability),
    CONSTRAINT fk_iam_group_capability_grants_group
        FOREIGN KEY (tenant_id, group_id)
        REFERENCES iam_groups (tenant_id, id)
        ON DELETE CASCADE,
    CONSTRAINT ck_iam_group_capability_grants_capability CHECK (
        capability IN (
            'IAM_ADMIN',
            'USERS_MANAGE',
            'GROUPS_READ',
            'GROUPS_MANAGE',
            'SOURCES_READ',
            'SOURCES_MANAGE',
            'SOURCES_DELETE'
        )
    )
);
CREATE OR REPLACE FUNCTION enforce_iam_group_capability_grant_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_system_key VARCHAR(16);
BEGIN
    SELECT group_record.system_key
    INTO target_system_key
    FROM iam_groups group_record
    WHERE group_record.tenant_id = NEW.tenant_id
      AND group_record.id = NEW.group_id;

    IF FOUND AND (
        (target_system_key = 'ADMIN' AND NEW.capability <> 'IAM_ADMIN')
        OR (target_system_key = 'BASIC')
        OR (target_system_key IS NULL AND NEW.capability = 'IAM_ADMIN')
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            CONSTRAINT = 'ck_iam_group_capability_grant_scope',
            MESSAGE = 'Capability is not grantable by this Group';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tr_iam_group_capability_grant_scope
BEFORE INSERT OR UPDATE OF tenant_id, group_id, capability
ON iam_group_capability_grants
FOR EACH ROW
EXECUTE FUNCTION enforce_iam_group_capability_grant_scope();


INSERT INTO iam_groups (tenant_id, id, name, system_key)
SELECT tenant.id, CAST('00000000-0000-0000-0000-000000000001' AS UUID), 'Admin', 'ADMIN'
FROM tenants tenant;

INSERT INTO iam_groups (tenant_id, id, name, system_key)
SELECT tenant.id, CAST('00000000-0000-0000-0000-000000000002' AS UUID), 'Basic', 'BASIC'
FROM tenants tenant;

INSERT INTO iam_group_capability_grants (tenant_id, group_id, capability)
SELECT tenant.id, CAST('00000000-0000-0000-0000-000000000001' AS UUID), 'IAM_ADMIN'
FROM tenants tenant;

INSERT INTO iam_group_memberships (tenant_id, group_id, actor_id)
SELECT membership.tenant_id,
       CAST('00000000-0000-0000-0000-000000000002' AS UUID),
       membership.actor_id
FROM tenant_memberships membership;

INSERT INTO iam_group_memberships (tenant_id, group_id, actor_id)
SELECT membership.tenant_id,
       CAST('00000000-0000-0000-0000-000000000001' AS UUID),
       membership.actor_id
FROM tenant_memberships membership
WHERE membership.role = 'OWNER';
