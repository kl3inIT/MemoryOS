-- Existing ownership is unknown and must not be inferred from provider identities.
ALTER TABLE connector_credential_pairs
    ADD COLUMN created_by_actor_id UUID,
    ADD CONSTRAINT fk_source_creator_membership FOREIGN KEY (tenant_id, created_by_actor_id)
        REFERENCES tenant_memberships (tenant_id, actor_id);
CREATE INDEX ix_source_creator ON connector_credential_pairs (tenant_id, created_by_actor_id)
    WHERE created_by_actor_id IS NOT NULL;

ALTER TABLE credentials
    ADD COLUMN owner_actor_id UUID,
    ADD CONSTRAINT fk_credential_owner_membership FOREIGN KEY (tenant_id, owner_actor_id)
        REFERENCES tenant_memberships (tenant_id, actor_id),
    ADD CONSTRAINT ck_credential_owner_kind CHECK (owner_actor_id IS NULL OR credential_kind = 'GOOGLE_OAUTH');
CREATE INDEX ix_credential_owner ON credentials (tenant_id, owner_actor_id)
    WHERE owner_actor_id IS NOT NULL;

ALTER TABLE google_drive_selection_operations
    ADD COLUMN group_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT ck_google_selection_group_ids CHECK (
        jsonb_typeof(group_ids) = 'array' AND jsonb_array_length(group_ids) <= 100
    );

ALTER TABLE google_drive_sources
    ADD COLUMN sync_paused BOOLEAN NOT NULL DEFAULT FALSE;

-- Retain only the explicitly authorized scoped creator's cleanup receipt access
-- after a private groupless Source has been physically removed.
ALTER TABLE connector_cleanup_attempts
    ADD COLUMN scope_owner_actor_id UUID,
    ADD CONSTRAINT fk_cleanup_scope_owner_membership FOREIGN KEY (tenant_id, scope_owner_actor_id)
        REFERENCES tenant_memberships (tenant_id, actor_id);

-- Scope policy changes even when explicit grants and memberships do not.
UPDATE tenants SET authorization_version = authorization_version + 1;
