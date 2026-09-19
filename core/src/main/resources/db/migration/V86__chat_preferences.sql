-- MEM-145: each member's Chat preferences (Onyx personalization and chat preferences), owned by Chat and bound to
-- the Tenant membership like chat_voice_settings. A deleted model clears the personal default.
CREATE TABLE chat_preferences (
    tenant_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    work_role VARCHAR(200) NOT NULL DEFAULT '',
    personal_preferences VARCHAR(2000) NOT NULL DEFAULT '',
    default_model_configuration_id UUID,
    start_page VARCHAR(16) NOT NULL DEFAULT 'CHAT',
    auto_scroll BOOLEAN NOT NULL DEFAULT TRUE,
    collapse_pastes BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (tenant_id, actor_id),
    FOREIGN KEY (tenant_id, actor_id) REFERENCES tenant_memberships(tenant_id, actor_id),
    FOREIGN KEY (tenant_id, default_model_configuration_id) REFERENCES model_configuration(tenant_id, id)
        ON DELETE SET NULL (default_model_configuration_id),
    CONSTRAINT ck_chat_preferences_start_page CHECK (start_page IN ('CHAT', 'SEARCH'))
);
