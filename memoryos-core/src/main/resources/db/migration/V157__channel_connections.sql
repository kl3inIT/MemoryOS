-- MEM-246: the Tenant's chat bots. Managing them is a capability of its own, granted through a Group.
ALTER TABLE iam_group_capability_grants DROP CONSTRAINT ck_iam_group_capability_grants_capability;
ALTER TABLE iam_group_capability_grants ADD CONSTRAINT ck_iam_group_capability_grants_capability CHECK (
    capability IN ('SYSTEM_ADMIN', 'SYSTEM_BASIC', 'USERS_MANAGE', 'GROUPS_MANAGE',
                   'SOURCES_MANAGE', 'MODELS_MANAGE', 'MCP_MANAGE', 'CHANNELS_MANAGE', 'AGENTS_CREATE', 'AGENTS_MANAGE',
                   'AUDIT_READ', 'CHAT_HISTORY_READ')
);

-- One row a bot. A Tenant may have several, as Onyx keeps several Slack bots and WeKnora several IM channels; one
-- provider app serves one bot, so two rows never hold the same app.
CREATE TABLE channel_connections (
    tenant_id UUID NOT NULL,
    id UUID NOT NULL,
    provider VARCHAR(16) NOT NULL,
    -- What the administrator calls the bot, which is also its service account's name.
    name VARCHAR(100) NOT NULL,
    -- Lark international or Feishu: an app exists on one of them, and its address follows from this.
    region VARCHAR(16) NOT NULL,
    app_id VARCHAR(64) NOT NULL,
    secret_ciphertext BYTEA NOT NULL,
    secret_nonce BYTEA NOT NULL,
    secret_key_version VARCHAR(64) NOT NULL,
    -- The language of the bot's own notices: a refusal, a busy reply, an invitation to a direct message.
    notice_language VARCHAR(2) NOT NULL,
    enabled BOOLEAN NOT NULL,
    -- The account a group question runs as (ServiceAccounts), owned by this row.
    service_account_id UUID NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT uq_channel_connection_id UNIQUE (id),
    CONSTRAINT uq_channel_connection_app UNIQUE (provider, region, app_id),
    CONSTRAINT fk_channel_connection_account FOREIGN KEY (tenant_id, service_account_id)
        REFERENCES tenant_memberships (tenant_id, actor_id),
    CONSTRAINT ck_channel_connection_provider CHECK (provider IN ('LARK')),
    CONSTRAINT ck_channel_connection_name CHECK (CHAR_LENGTH(BTRIM(name)) BETWEEN 1 AND 100),
    CONSTRAINT ck_channel_connection_region CHECK (region IN ('LARK', 'FEISHU')),
    CONSTRAINT ck_channel_connection_app_id CHECK (provider <> 'LARK' OR app_id ~ '^cli_[0-9a-z]{8,40}$'),
    CONSTRAINT ck_channel_connection_language CHECK (notice_language IN ('vi', 'en')),
    CONSTRAINT ck_channel_connection_secret_envelope CHECK (
        OCTET_LENGTH(secret_ciphertext) BETWEEN 17 AND 1040
        AND OCTET_LENGTH(secret_nonce) = 12
        AND CHAR_LENGTH(secret_key_version) BETWEEN 1 AND 64)
);

CREATE INDEX ix_channel_connections_enabled ON channel_connections (provider) WHERE enabled;
