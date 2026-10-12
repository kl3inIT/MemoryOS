-- MEM-198: System One services a Tenant connects for typed classification. Unlike the Web, image and voice
-- connections a Tenant may hold several of one type (two self-hosted servers, two gateway models), so a connection is
-- named and carries its own model.
CREATE TABLE system_one_connection (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    provider VARCHAR(32) NOT NULL
        CHECK (provider IN ('TYPESAFE', 'CLOUDFLARE', 'NINEROUTER', 'LAYA', 'SYSTEMONE_COMPATIBLE')),
    name VARCHAR(80) NOT NULL CHECK (name <> ''),
    endpoint VARCHAR(2048) NOT NULL DEFAULT '',
    model VARCHAR(200) NOT NULL CHECK (model <> ''),
    credential TEXT,
    data_boundary VARCHAR(16) NOT NULL DEFAULT 'EXTERNAL' CHECK (data_boundary IN ('INTERNAL', 'EXTERNAL')),
    -- USD per million input tokens; null when the administrator does not know it.
    input_price NUMERIC(12, 6) CHECK (input_price >= 0),
    revision BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX system_one_connection_name ON system_one_connection(tenant_id, lower(name));

-- A classifying task runs on a language model or on a System One connection, never both. A connection a task runs
-- on cannot be deleted.
ALTER TABLE model_flow_default ADD COLUMN system_one_connection_id UUID;
ALTER TABLE model_flow_default ADD CONSTRAINT model_flow_default_system_one_fk
    FOREIGN KEY (tenant_id, system_one_connection_id) REFERENCES system_one_connection(tenant_id, id);
ALTER TABLE model_flow_default ADD CONSTRAINT model_flow_default_one_classifier
    CHECK (system_one_connection_id IS NULL
        OR (flow = 'CHAT_GUARDRAIL' AND model_configuration_id IS NULL AND reasoning_effort IS NULL));
