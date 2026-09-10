ALTER TABLE chat_message
    ADD COLUMN sources jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT chat_message_sources_valid CHECK (
        jsonb_typeof(sources) = 'array' AND jsonb_array_length(sources) <= 24
        AND octet_length(sources::text) <= 131072
        AND (role = 'ASSISTANT' OR sources = '[]'::jsonb)
    );
