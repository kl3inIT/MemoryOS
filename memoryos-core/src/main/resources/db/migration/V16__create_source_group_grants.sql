CREATE TABLE source_group_grants (
    tenant_id UUID NOT NULL,
    connector_credential_pair_id UUID NOT NULL,
    group_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_source_group_grants PRIMARY KEY (tenant_id, connector_credential_pair_id, group_id),
    CONSTRAINT fk_source_group_grants_pair FOREIGN KEY (tenant_id, connector_credential_pair_id)
        REFERENCES connector_credential_pairs (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_source_group_grants_group FOREIGN KEY (tenant_id, group_id)
        REFERENCES iam_groups (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_source_group_grants_group
    ON source_group_grants (tenant_id, group_id, connector_credential_pair_id);

INSERT INTO source_group_grants (tenant_id, connector_credential_pair_id, group_id)
SELECT pair.tenant_id, pair.id, admin_group.id
FROM connector_credential_pairs pair
JOIN iam_groups admin_group
  ON admin_group.tenant_id = pair.tenant_id
 AND admin_group.system_key = 'ADMIN';
