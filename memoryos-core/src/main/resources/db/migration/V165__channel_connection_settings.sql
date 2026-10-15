-- MEM-263: a bot's app is known in its provider's own terms. The region was Lark's; it moves into the provider's
-- public settings, which another provider fills with its own (Zalo's transport).
ALTER TABLE channel_connections ADD COLUMN settings JSONB NOT NULL DEFAULT '{}';
UPDATE channel_connections SET settings = jsonb_build_object('region', region);

ALTER TABLE channel_connections DROP CONSTRAINT uq_channel_connection_app;
ALTER TABLE channel_connections DROP CONSTRAINT ck_channel_connection_region;
ALTER TABLE channel_connections DROP COLUMN region;

-- One provider app serves one bot: an app's id names it within its provider.
ALTER TABLE channel_connections ADD CONSTRAINT uq_channel_connection_app UNIQUE (provider, app_id);
ALTER TABLE channel_connections ADD CONSTRAINT ck_channel_connection_settings CHECK (jsonb_typeof(settings) = 'object');
ALTER TABLE channel_connections ADD CONSTRAINT ck_channel_connection_lark_region CHECK (
    provider <> 'LARK' OR settings ->> 'region' IN ('LARK', 'FEISHU'));
