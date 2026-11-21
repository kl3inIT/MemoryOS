-- ADR 0032: the chat bots (MEM-246, MEM-263, MEM-283, MEM-285, MEM-298, MEM-299) are removed; MemoryOS serves the
-- capstone's scope only. This drops what the channel migrations V157, V158, V160, V162, V163, V165, V167, V168, V170,
-- V183, V185, V187, V192, V193, V194, V195 (public bots) and V196 created for them. Conversations the bots held stay
-- with their owners (chat_session.surface, V156), and so do the bots' service accounts (V155): nothing is deleted
-- that a person may still read in the chat history or the audit log.

-- The capability to manage bots. Groups that held it lose it, and their Tenants' cached authorization is renewed.
WITH revoked AS (
    DELETE FROM iam_group_capability_grants
    WHERE capability = 'CHANNELS_MANAGE'
    RETURNING tenant_id
)
UPDATE tenants
SET authorization_version = authorization_version + 1
WHERE id IN (SELECT tenant_id FROM revoked);

ALTER TABLE iam_group_capability_grants DROP CONSTRAINT ck_iam_group_capability_grants_capability;
ALTER TABLE iam_group_capability_grants ADD CONSTRAINT ck_iam_group_capability_grants_capability CHECK (
    capability IN ('SYSTEM_ADMIN', 'SYSTEM_BASIC', 'USERS_MANAGE', 'GROUPS_MANAGE',
                   'SOURCES_MANAGE', 'MODELS_MANAGE', 'MCP_MANAGE', 'AGENTS_CREATE', 'AGENTS_MANAGE',
                   'AUDIT_READ', 'CHAT_HISTORY_READ')
);

-- The Worker's daily sweep of the messages bots received, which no Worker schedules any more.
DELETE FROM scheduled_tasks WHERE task_name = 'memoryos-channel-message-retention-v1';

-- The WhatsApp bridge's schema: the linked devices (V192) and the whatsmeow session store the bridge kept in it.
DROP SCHEMA IF EXISTS whatsapp CASCADE;

-- Chat's bindings of bots and group chats to assistants (V183, V185), and Chat's record of a conversation's bot.
DROP TABLE chat_channel_group_binding;
DROP TABLE chat_channel_binding;
ALTER TABLE chat_session
    DROP COLUMN channel_connection_id,
    DROP COLUMN channel_chat_id,
    DROP COLUMN channel_public;

-- The assistant a member's direct messages to a bot answered with (V196).
ALTER TABLE chat_preferences DROP CONSTRAINT fk_chat_preferences_channel_persona;
ALTER TABLE chat_preferences DROP COLUMN channel_persona_id;

-- Whether a public bot could use an MCP server (V195); the servers open through chosen assistants stay.
ALTER TABLE mcp_server DROP CONSTRAINT ck_mcp_server_public_bots_shared;
ALTER TABLE mcp_server DROP COLUMN public_bots;

-- The bots themselves and everything they kept (V157 to V194).
DROP TABLE channel_pairings;
DROP TABLE channel_groups;
DROP TABLE channel_account_links;
DROP TABLE channel_link_codes;
DROP TABLE channel_connection_state;
DROP TABLE channel_messages;
DROP TABLE channel_conversations;
DROP TABLE channel_identities;
DROP TABLE channel_connections;
