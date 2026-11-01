-- Flyway executes this migration in one transaction; the table lock prevents
-- concurrent writes from recreating direct read grants during the cutover.
LOCK TABLE iam_group_capability_grants IN ACCESS EXCLUSIVE MODE;

WITH revoked AS (
    DELETE FROM iam_group_capability_grants
    WHERE capability = 'GROUPS_READ'
    RETURNING tenant_id
)
UPDATE tenants
SET authorization_version = authorization_version + 1
WHERE id IN (SELECT tenant_id FROM revoked);

ALTER TABLE iam_group_capability_grants
    DROP CONSTRAINT ck_iam_group_capability_grants_capability;

ALTER TABLE iam_group_capability_grants
    ADD CONSTRAINT ck_iam_group_capability_grants_capability CHECK (
        capability IN (
            'SYSTEM_ADMIN',
            'SYSTEM_BASIC',
            'USERS_MANAGE',
            'GROUPS_MANAGE',
            'SOURCES_READ',
            'SOURCES_MANAGE',
            'SOURCES_DELETE',
            'MODELS_MANAGE'
        )
    );
