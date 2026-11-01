-- Source dates are independent of connector/pipeline state timestamps.
ALTER TABLE connector_items ADD COLUMN source_created_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE connector_items ADD COLUMN source_updated_at TIMESTAMP WITH TIME ZONE;

-- For uploaded FILE content the source event is upload. Do not call extraction/reindex an update.
UPDATE connector_items i SET source_created_at=i.created_at, source_updated_at=v.created_at
FROM connectors c, connector_item_versions v
WHERE c.tenant_id=i.tenant_id AND c.id=i.connector_id AND c.connector_type='FILE'
    AND v.tenant_id=i.tenant_id AND v.id=i.current_version_id;

-- Remote dates remain NULL: the current provider ingestion does not capture source dates.
-- Never substitute local pipeline timestamps for missing provider metadata.
