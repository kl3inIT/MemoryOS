-- PostgreSQL validates this temporary constraint against existing rows before any IAM
-- schema change. Its name is the operator-facing action when historical ADMIN rows exist.
ALTER TABLE tenant_memberships
    ADD CONSTRAINT ck_v14_reconcile_admin_as_owner_or_member_before_iam
        CHECK (role <> 'ADMIN');

ALTER TABLE actors
    ADD COLUMN account_type VARCHAR(16) DEFAULT 'STANDARD' NOT NULL;

ALTER TABLE actors
    ADD CONSTRAINT ck_actors_account_type
        CHECK (account_type = 'STANDARD');

ALTER TABLE tenants
    ADD COLUMN authorization_version BIGINT DEFAULT 0 NOT NULL;

ALTER TABLE tenants
    ADD CONSTRAINT ck_tenants_authorization_version
        CHECK (authorization_version >= 0);

ALTER TABLE tenant_memberships
    DROP CONSTRAINT ck_tenant_memberships_role;

ALTER TABLE tenant_memberships
    DROP CONSTRAINT ck_v14_reconcile_admin_as_owner_or_member_before_iam;

ALTER TABLE tenant_memberships
    ADD CONSTRAINT ck_tenant_memberships_role
        CHECK (role IN ('OWNER', 'MEMBER'));

-- ActorId moved packages. Serialized principals from an older binary cannot be deserialized safely.
-- Cascading session attributes forces a deliberate re-login and forbids a mixed-version API rollout.
DELETE FROM spring_session;
