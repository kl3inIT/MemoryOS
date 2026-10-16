-- MEM-263: a Zalo bot. Its app is the bot itself, named by the number before the colon of its token; how it receives
-- (Zalo pushing to the deployment's address, or the deployment asking) is the administrator's choice, kept in its
-- settings.
ALTER TABLE channel_connections DROP CONSTRAINT ck_channel_connection_provider;
ALTER TABLE channel_connections ADD CONSTRAINT ck_channel_connection_provider CHECK (provider IN ('LARK', 'ZALO_BOT'));
ALTER TABLE channel_connections ADD CONSTRAINT ck_channel_connection_zalo_app_id CHECK (
    provider <> 'ZALO_BOT' OR app_id ~ '^[0-9]{1,32}$');
ALTER TABLE channel_connections ADD CONSTRAINT ck_channel_connection_zalo_transport CHECK (
    provider <> 'ZALO_BOT' OR settings ->> 'transport' IN ('WEBHOOK', 'POLLING'));
