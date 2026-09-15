-- MEM-91: per-Tenant speech-to-text and text-to-speech provider connections (Onyx voice_provider parity).
-- One row per implemented provider; each function has at most one Tenant default. Audio is never stored.
CREATE TABLE chat_voice_connection (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    provider VARCHAR(32) NOT NULL,
    endpoint VARCHAR(2048) NOT NULL DEFAULT '',
    credential TEXT,
    stt_model VARCHAR(200) NOT NULL DEFAULT '',
    tts_model VARCHAR(200) NOT NULL DEFAULT '',
    tts_voice VARCHAR(200) NOT NULL DEFAULT '',
    stt_active BOOLEAN NOT NULL DEFAULT FALSE,
    tts_active BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, provider),
    CONSTRAINT ck_chat_voice_connection_provider CHECK (provider IN ('OPENAI', 'OPENAI_COMPATIBLE')),
    CONSTRAINT ck_chat_voice_connection_stt_model CHECK (NOT stt_active OR stt_model <> ''),
    CONSTRAINT ck_chat_voice_connection_tts_model CHECK (NOT tts_active OR (tts_model <> '' AND tts_voice <> ''))
);
CREATE UNIQUE INDEX chat_voice_stt_default ON chat_voice_connection(tenant_id) WHERE stt_active;
CREATE UNIQUE INDEX chat_voice_tts_default ON chat_voice_connection(tenant_id) WHERE tts_active;
