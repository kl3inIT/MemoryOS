-- MEM-91: each member's voice preferences (Onyx user.voice_auto_send, voice_auto_playback, voice_playback_speed),
-- owned by Chat and bound to the Tenant membership rather than the IAM account row.
CREATE TABLE chat_voice_settings (
    tenant_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    auto_send BOOLEAN NOT NULL DEFAULT FALSE,
    auto_playback BOOLEAN NOT NULL DEFAULT FALSE,
    playback_speed DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    PRIMARY KEY (tenant_id, actor_id),
    FOREIGN KEY (tenant_id, actor_id) REFERENCES tenant_memberships(tenant_id, actor_id),
    CONSTRAINT ck_chat_voice_settings_speed CHECK (playback_speed BETWEEN 0.5 AND 2.0)
);
