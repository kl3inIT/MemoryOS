-- Flyway executes this migration in one transaction; the table lock prevents
-- concurrent writes while the grant constraint and scope trigger are replaced.
LOCK TABLE iam_group_capability_grants IN ACCESS EXCLUSIVE MODE;

DROP TRIGGER tr_iam_group_capability_grant_scope ON iam_group_capability_grants;

ALTER TABLE iam_group_capability_grants
    DROP CONSTRAINT ck_iam_group_capability_grants_capability;

WITH renamed AS (
    UPDATE iam_group_capability_grants
    SET capability = CASE capability
        WHEN 'IAM_ADMIN' THEN 'SYSTEM_ADMIN'
        WHEN 'BASIC_ACCESS' THEN 'SYSTEM_BASIC'
    END
    WHERE capability IN ('IAM_ADMIN', 'BASIC_ACCESS')
    RETURNING tenant_id
)
UPDATE tenants
SET authorization_version = authorization_version + 1
WHERE id IN (SELECT tenant_id FROM renamed);

ALTER TABLE iam_group_capability_grants
    ADD CONSTRAINT ck_iam_group_capability_grants_capability CHECK (
        capability IN (
            'SYSTEM_ADMIN',
            'SYSTEM_BASIC',
            'USERS_MANAGE',
            'GROUPS_READ',
            'GROUPS_MANAGE',
            'SOURCES_READ',
            'SOURCES_MANAGE',
            'SOURCES_DELETE',
            'MODELS_MANAGE'
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
        (target_system_key = 'ADMIN' AND NEW.capability <> 'SYSTEM_ADMIN')
        OR (target_system_key = 'BASIC' AND NEW.capability <> 'SYSTEM_BASIC')
        OR (target_system_key IS NULL AND NEW.capability IN ('SYSTEM_ADMIN', 'SYSTEM_BASIC', 'SEARCH_READ'))
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
