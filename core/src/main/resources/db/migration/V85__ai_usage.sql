-- MEM-98: daily AI usage rollup (Onyx user_usage). One accumulating row per Tenant, actor, UTC day, flow,
-- provider, model name and data boundary; names are the key so a deleted model keeps its history without colliding rows.
CREATE TABLE ai_usage (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    -- Null for system work such as document indexing. Actors are never deleted, so spend stays attributed.
    actor_id UUID REFERENCES actors(id),
    day DATE NOT NULL,
    flow VARCHAR(32) NOT NULL CHECK (flow IN ('CHAT', 'CHAT_NAMING', 'DEEP_RESEARCH', 'EMBEDDING_QUERY',
        'EMBEDDING_INDEXING', 'IMAGE_GENERATION', 'IMAGE_EDIT', 'SPEECH_TO_TEXT', 'TEXT_TO_SPEECH')),
    provider_name VARCHAR(200) NOT NULL,
    model_name VARCHAR(200) NOT NULL,
    -- Catalog references for navigation only; history does not depend on them.
    provider_id UUID,
    model_configuration_id UUID,
    -- Copied at call time so a later relabel does not rewrite history; null outside the chat catalog.
    data_boundary VARCHAR(16) CHECK (data_boundary IN ('INTERNAL', 'EXTERNAL')),
    calls BIGINT NOT NULL DEFAULT 0 CHECK (calls >= 0),
    input_tokens BIGINT NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens BIGINT NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    cache_read_tokens BIGINT NOT NULL DEFAULT 0 CHECK (cache_read_tokens >= 0),
    image_count BIGINT NOT NULL DEFAULT 0 CHECK (image_count >= 0),
    audio_seconds NUMERIC(14, 3) NOT NULL DEFAULT 0 CHECK (audio_seconds >= 0),
    -- Sum of known costs only; calls without a price or without reported usage are counted separately.
    cost_usd NUMERIC(18, 8) NOT NULL DEFAULT 0 CHECK (cost_usd >= 0),
    unknown_cost_calls BIGINT NOT NULL DEFAULT 0 CHECK (unknown_cost_calls >= 0 AND unknown_cost_calls <= calls),
    CONSTRAINT uq_ai_usage_dims UNIQUE NULLS NOT DISTINCT (tenant_id, actor_id, day, flow, provider_name, model_name, data_boundary)
);
CREATE INDEX ix_ai_usage_tenant_day ON ai_usage (tenant_id, day);
