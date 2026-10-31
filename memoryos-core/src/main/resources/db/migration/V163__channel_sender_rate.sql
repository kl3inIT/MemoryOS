-- MEM-246: how many messages one person sent a bot in the last minute, read at every message.
CREATE INDEX ix_channel_messages_sender ON channel_messages (connection_id, external_user_id, received_at);
