-- MEM-283: a WhatsApp bot, and a lease for each bot so that several channel processes share them.

-- A WhatsApp bot is a number linked as a companion device; its app id is the number in international form without
-- the plus. Its session is the bridge's, in the schema below, so it has no secret of its own.
ALTER TABLE channel_connections DROP CONSTRAINT ck_channel_connection_provider;
ALTER TABLE channel_connections ADD CONSTRAINT ck_channel_connection_provider CHECK (
    provider IN ('LARK', 'ZALO_BOT', 'WHATSAPP'));
ALTER TABLE channel_connections ADD CONSTRAINT ck_channel_connection_whatsapp_app_id CHECK (
    provider <> 'WHATSAPP' OR app_id ~ '^[1-9][0-9]{7,14}$');
ALTER TABLE channel_connections
    ALTER COLUMN secret_ciphertext DROP NOT NULL,
    ALTER COLUMN secret_nonce DROP NOT NULL,
    ALTER COLUMN secret_key_version DROP NOT NULL,
    ADD CONSTRAINT ck_channel_connection_secret CHECK (
        CASE WHEN provider = 'WHATSAPP'
            THEN secret_ciphertext IS NULL AND secret_nonce IS NULL AND secret_key_version IS NULL
            ELSE secret_ciphertext IS NOT NULL AND secret_nonce IS NOT NULL AND secret_key_version IS NOT NULL END);

-- The bridge's whatsmeow session store, whose tables whatsmeow creates and migrates itself; Flyway owns only the
-- schema.
CREATE SCHEMA IF NOT EXISTS whatsapp;

-- The process holding a bot, until when, and the epoch of its hold: a new one each time the bot changes hands, which
-- the holder's writes carry so that one that lost the bot cannot overwrite its successor. The address is where a
-- process that does not hold the bot passes a webhook request.
ALTER TABLE channel_connection_state
    ADD COLUMN holder VARCHAR(128),
    ADD COLUMN holder_address VARCHAR(255),
    ADD COLUMN holder_since TIMESTAMP WITH TIME ZONE,
    ADD COLUMN lease_until TIMESTAMP WITH TIME ZONE,
    ADD COLUMN lease_epoch BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_channel_connection_state_lease CHECK (
        (holder IS NULL AND holder_address IS NULL AND holder_since IS NULL AND lease_until IS NULL)
        OR (holder IS NOT NULL AND holder_address IS NOT NULL AND holder_since IS NOT NULL AND lease_until IS NOT NULL));

-- The code a bot being linked shows, scanned in the platform's app; written by the holder, read by the bot's page.
CREATE TABLE channel_pairings (
    connection_id UUID PRIMARY KEY REFERENCES channel_connections (id) ON DELETE CASCADE,
    code VARCHAR(512) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
