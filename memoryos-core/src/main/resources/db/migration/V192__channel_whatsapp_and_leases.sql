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

-- A number is no app the platform vouches for until it is linked, so adding a bot reserves it only within its Tenant;
-- a number links one bot across every Tenant (whatsapp.channel_devices), so no Tenant can hold another's number by
-- adding it first.
ALTER TABLE channel_connections DROP CONSTRAINT uq_channel_connection_app;
CREATE UNIQUE INDEX uq_channel_connection_app ON channel_connections (provider, app_id) WHERE provider <> 'WHATSAPP';
CREATE UNIQUE INDEX uq_channel_connection_whatsapp_number ON channel_connections (tenant_id, app_id)
    WHERE provider = 'WHATSAPP';

-- The bridge's whatsmeow session store, whose whatsmeow_* tables whatsmeow creates and migrates itself.
CREATE SCHEMA IF NOT EXISTS whatsapp;

-- The device each WhatsApp bot linked, written by the bridge when the bot's own number scans its code. A session is
-- found by its bot, never by its number, so a bot added later for the same number links afresh rather than inherit a
-- removed bot's session; removing the bot removes the row, and the bridge logs out a device no bot names. A number
-- links one bot.
CREATE TABLE whatsapp.channel_devices (
    connection_id UUID PRIMARY KEY REFERENCES public.channel_connections (id) ON DELETE CASCADE,
    device_jid VARCHAR(128) NOT NULL UNIQUE,
    phone VARCHAR(15) NOT NULL UNIQUE,
    linked_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

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
