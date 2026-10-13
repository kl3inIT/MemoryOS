-- MEM-246: what the process that holds a bot's connection last saw of it, for the administrator's page.
CREATE TABLE channel_connection_state (
    connection_id UUID PRIMARY KEY REFERENCES channel_connections (id) ON DELETE CASCADE,
    -- Since when the connection has been open; null while it is not.
    opened_at TIMESTAMP WITH TIME ZONE,
    -- The typed code of the last failure to open or keep it, and when; never a provider message.
    last_failure_code VARCHAR(64),
    last_failure_at TIMESTAMP WITH TIME ZONE,
    last_message_at TIMESTAMP WITH TIME ZONE,
    -- Written at every pass of the supervisor: an old heartbeat means no process holds the bot.
    heartbeat_at TIMESTAMP WITH TIME ZONE NOT NULL
);
