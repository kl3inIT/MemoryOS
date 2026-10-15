-- MEM-263: a member's account on a platform that names no e-mail, linked once with a code made in MemoryOS.

-- The code a member made for one bot: only its hash is kept, one live code a member and bot, replaced by a new one.
CREATE TABLE channel_link_codes (
    tenant_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    code_hash BYTEA NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (connection_id, actor_id),
    CONSTRAINT uq_channel_link_code UNIQUE (connection_id, code_hash),
    CONSTRAINT fk_channel_link_code_connection FOREIGN KEY (tenant_id, connection_id)
        REFERENCES channel_connections (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_channel_link_code_actor FOREIGN KEY (tenant_id, actor_id)
        REFERENCES tenant_memberships (tenant_id, actor_id) ON DELETE CASCADE
);

-- Which member a platform user is, for one bot: one platform user a member and one member a platform user.
CREATE TABLE channel_account_links (
    tenant_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    external_user_id VARCHAR(128) NOT NULL,
    actor_id UUID NOT NULL,
    linked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (connection_id, external_user_id),
    CONSTRAINT uq_channel_account_link_actor UNIQUE (connection_id, actor_id),
    CONSTRAINT fk_channel_account_link_connection FOREIGN KEY (tenant_id, connection_id)
        REFERENCES channel_connections (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_channel_account_link_actor FOREIGN KEY (tenant_id, actor_id)
        REFERENCES tenant_memberships (tenant_id, actor_id) ON DELETE CASCADE
);
CREATE INDEX ix_channel_account_link_actor ON channel_account_links (tenant_id, actor_id);

-- The wrong codes and linking notices of a sender are counted from what it sent within the hour.
CREATE INDEX ix_channel_message_sender_reason ON channel_messages (connection_id, external_user_id, reason, received_at)
    WHERE reason IS NOT NULL;
