-- MEM-102: per-task model defaults (Onyx llm_model_flow) and the provider data boundary.
CREATE TABLE model_flow_default (
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    flow VARCHAR(32) NOT NULL CHECK (flow IN ('CHAT_NAMING')),
    model_configuration_id UUID,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    PRIMARY KEY (tenant_id, flow),
    FOREIGN KEY (tenant_id, model_configuration_id) REFERENCES model_configuration(tenant_id, id)
        ON DELETE SET NULL (model_configuration_id)
);
INSERT INTO model_flow_default(tenant_id, flow) SELECT tenant_id, 'CHAT_NAMING' FROM chat_model_default;

ALTER TABLE llm_provider ADD COLUMN data_boundary VARCHAR(16) NOT NULL DEFAULT 'EXTERNAL'
    CHECK (data_boundary IN ('INTERNAL', 'EXTERNAL'));
