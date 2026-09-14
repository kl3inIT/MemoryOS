-- Match application mutation lock order before changing authorization scope.
SELECT id FROM tenants ORDER BY id FOR UPDATE;
LOCK TABLE source_group_grants IN ACCESS EXCLUSIVE MODE;

UPDATE tenants
SET authorization_version = authorization_version + 1
WHERE id IN (
    SELECT source_grant.tenant_id
    FROM source_group_grants source_grant
    JOIN iam_groups group_record
      ON group_record.tenant_id = source_grant.tenant_id
     AND group_record.id = source_grant.group_id
    WHERE group_record.system_key IS NOT NULL
);

-- System membership and capability grants remain untouched. Admin Source access
-- comes from SYSTEM_ADMIN, not from source_group_grants.
DELETE FROM source_group_grants source_grant
USING iam_groups group_record
WHERE group_record.tenant_id = source_grant.tenant_id
  AND group_record.id = source_grant.group_id
  AND group_record.system_key IS NOT NULL;

CREATE FUNCTION enforce_ordinary_source_group() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    target_system_key VARCHAR;
BEGIN
    SELECT system_key INTO target_system_key
    FROM iam_groups
    WHERE tenant_id = NEW.tenant_id AND id = NEW.group_id
    FOR SHARE;

    IF FOUND AND target_system_key IS NOT NULL THEN
        RAISE EXCEPTION 'Source associations require an ordinary Group'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_source_group_grants_ordinary_group';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_source_group_grants_ordinary_group
BEFORE INSERT OR UPDATE OF tenant_id, group_id ON source_group_grants
FOR EACH ROW EXECUTE FUNCTION enforce_ordinary_source_group();

CREATE FUNCTION prevent_associated_group_becoming_system() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM source_group_grants
        WHERE tenant_id = OLD.tenant_id AND group_id = OLD.id
    ) THEN
        RAISE EXCEPTION 'A Source-associated Group cannot become a system Group'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_source_group_grants_ordinary_group';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_iam_groups_source_association_kind
BEFORE UPDATE OF system_key ON iam_groups
FOR EACH ROW
WHEN (NEW.system_key IS NOT NULL AND OLD.system_key IS DISTINCT FROM NEW.system_key)
EXECUTE FUNCTION prevent_associated_group_becoming_system();
