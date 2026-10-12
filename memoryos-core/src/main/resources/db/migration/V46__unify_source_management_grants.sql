-- Flyway executes this migration in one transaction; the table lock prevents
-- concurrent grant changes while affected authorization revisions are invalidated.
LOCK TABLE iam_group_capability_grants IN ACCESS EXCLUSIVE MODE;

-- Existing managers gain effective delete authority even when no grants are revoked.
-- Capture every affected tenant before deleting the standalone read/delete grants.
UPDATE tenants
SET authorization_version = authorization_version + 1
WHERE id IN (
    SELECT tenant_id
    FROM iam_group_capability_grants
    WHERE capability IN ('SOURCES_MANAGE', 'SOURCES_READ', 'SOURCES_DELETE')
);

DELETE FROM iam_group_capability_grants
WHERE capability IN ('SOURCES_READ', 'SOURCES_DELETE');

ALTER TABLE iam_group_capability_grants
    DROP CONSTRAINT ck_iam_group_capability_grants_capability;

ALTER TABLE iam_group_capability_grants
    ADD CONSTRAINT ck_iam_group_capability_grants_capability CHECK (
        capability IN (
            'SYSTEM_ADMIN',
            'SYSTEM_BASIC',
            'USERS_MANAGE',
            'GROUPS_MANAGE',
            'SOURCES_MANAGE',
            'MODELS_MANAGE'
        )
    );
