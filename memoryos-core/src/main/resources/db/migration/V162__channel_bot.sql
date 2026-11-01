-- MEM-246: what the platform said of a bot's app when it was last checked: its bot, and the scopes it refused.
ALTER TABLE channel_connections ADD COLUMN bot_name VARCHAR(255);
ALTER TABLE channel_connections ADD COLUMN bot_external_id VARCHAR(128);
ALTER TABLE channel_connections ADD COLUMN missing_scopes VARCHAR(1024) NOT NULL DEFAULT '';
