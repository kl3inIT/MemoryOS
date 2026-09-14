CREATE TABLE chat_image_connection (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    provider VARCHAR(32) NOT NULL,
    endpoint VARCHAR(2048) NOT NULL DEFAULT '',
    model VARCHAR(200) NOT NULL DEFAULT '',
    credential TEXT,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, provider),
    CHECK (provider IN ('OPENAI_IMAGE'))
);
CREATE UNIQUE INDEX chat_image_default ON chat_image_connection(tenant_id) WHERE active;
