ALTER TABLE chat_message
    ADD COLUMN artifacts jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT chat_message_artifacts_bounded CHECK (
        jsonb_typeof(artifacts) = 'array'
        AND jsonb_array_length(artifacts) <= 3
        AND octet_length(artifacts::text) <= 131072
    );
