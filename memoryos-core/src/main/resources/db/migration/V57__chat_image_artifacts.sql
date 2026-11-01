CREATE TABLE chat_image_artifact (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    message_id UUID NOT NULL,
    stored_object_id UUID NOT NULL,
    object_key VARCHAR(240) NOT NULL,
    media_type VARCHAR(100) NOT NULL,
    revised_prompt TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX chat_image_artifact_message ON chat_image_artifact(tenant_id, message_id);
