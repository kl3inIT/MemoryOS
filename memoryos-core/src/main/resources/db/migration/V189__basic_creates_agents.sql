-- MEM-281: every member creates assistants by default, as Onyx grants ADD_AGENTS to every user of its seeded Basic
-- group. The protected Basic Group may now hold AGENTS_CREATE beside SYSTEM_BASIC, and a full administrator switches it
-- off. Basic could hold nothing else until now, so no Tenant has chosen otherwise and every Basic Group gains it.
LOCK TABLE iam_group_capability_grants IN ACCESS EXCLUSIVE MODE;

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
        OR (target_system_key = 'BASIC' AND NEW.capability NOT IN ('SYSTEM_BASIC', 'AGENTS_CREATE'))
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

WITH granted AS (
    INSERT INTO iam_group_capability_grants (tenant_id, group_id, capability)
    SELECT tenant_id, id, 'AGENTS_CREATE'
    FROM iam_groups
    WHERE system_key = 'BASIC'
    ON CONFLICT DO NOTHING
    RETURNING tenant_id
)
UPDATE tenants
SET authorization_version = authorization_version + 1
WHERE id IN (SELECT tenant_id FROM granted);
