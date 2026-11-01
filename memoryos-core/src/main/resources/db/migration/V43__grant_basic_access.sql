ALTER TABLE iam_group_capability_grants
    DROP CONSTRAINT ck_iam_group_capability_grants_capability;

ALTER TABLE iam_group_capability_grants
    ADD CONSTRAINT ck_iam_group_capability_grants_capability CHECK (
        capability IN (
            'IAM_ADMIN',
            'BASIC_ACCESS',
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
        (target_system_key = 'ADMIN' AND NEW.capability <> 'IAM_ADMIN')
        OR (target_system_key = 'BASIC' AND NEW.capability <> 'BASIC_ACCESS')
        OR (target_system_key IS NULL AND NEW.capability IN ('IAM_ADMIN', 'BASIC_ACCESS', 'SEARCH_READ'))
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            CONSTRAINT = 'ck_iam_group_capability_grant_scope',
            MESSAGE = 'Capability is not grantable by this Group';
    END IF;
    RETURN NEW;
END
$$;

WITH granted AS (
    INSERT INTO iam_group_capability_grants (tenant_id, group_id, capability)
    SELECT tenant_id, id, 'BASIC_ACCESS'
    FROM iam_groups
    WHERE system_key = 'BASIC'
    ON CONFLICT DO NOTHING
    RETURNING tenant_id
)
UPDATE tenants
SET authorization_version = authorization_version + 1
WHERE id IN (SELECT tenant_id FROM granted);
