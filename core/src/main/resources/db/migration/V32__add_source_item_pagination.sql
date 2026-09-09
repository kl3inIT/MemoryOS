CREATE INDEX ix_source_item_page ON connector_items (tenant_id, connector_id, created_at DESC, id DESC)
    WHERE current_version_id IS NOT NULL;
CREATE INDEX ix_index_attempt_item_latest ON index_attempts
    (tenant_id, connector_credential_pair_id, connector_item_id, pair_sequence DESC);
