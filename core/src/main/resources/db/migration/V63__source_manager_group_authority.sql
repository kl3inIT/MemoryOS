-- Group attachment moves from "the actor manages every associated Group" to one recorded Source
-- manager. Scoped creators keep the authority they already had; Sources with no recorded creator
-- (pre-V47) stay administrator-only until an administrator appoints a manager.
ALTER TABLE connector_credential_pairs
    ADD COLUMN manager_actor_id UUID,
    ADD CONSTRAINT fk_source_manager_membership FOREIGN KEY (tenant_id, manager_actor_id)
        REFERENCES tenant_memberships (tenant_id, actor_id);
CREATE INDEX ix_source_manager ON connector_credential_pairs (tenant_id, manager_actor_id)
    WHERE manager_actor_id IS NOT NULL;

UPDATE connector_credential_pairs
SET manager_actor_id = created_by_actor_id
WHERE created_by_actor_id IS NOT NULL;

-- Scope policy changes even when explicit grants and memberships do not.
UPDATE tenants SET authorization_version = authorization_version + 1;
