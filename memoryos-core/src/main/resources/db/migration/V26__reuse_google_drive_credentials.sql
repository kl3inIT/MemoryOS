ALTER TABLE credentials ADD COLUMN name VARCHAR(120);
UPDATE credentials credential
SET name = LEFT(google.account_email, 120)
FROM google_drive_credentials google
WHERE google.tenant_id = credential.tenant_id AND google.credential_id = credential.id;
UPDATE credentials SET name = 'No authentication' WHERE credential_kind = 'NO_AUTH';
ALTER TABLE credentials ALTER COLUMN name SET NOT NULL;
ALTER TABLE credentials ADD CONSTRAINT ck_credentials_name CHECK (CHAR_LENGTH(BTRIM(name)) BETWEEN 1 AND 120);

ALTER TABLE credentials DROP CONSTRAINT uq_credentials_tenant_kind;
CREATE UNIQUE INDEX uq_no_auth_credential_per_tenant ON credentials (tenant_id, credential_kind)
    WHERE credential_kind = 'NO_AUTH';
DROP INDEX uq_google_drive_connector_per_tenant;
CREATE INDEX ix_pairs_tenant_credential ON connector_credential_pairs (tenant_id, credential_id, id);
