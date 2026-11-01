-- As Onyx, a turn has no citation count cap; the column keeps a storage byte bound. Existing rows satisfy the
-- looser check, so NOT VALID avoids scanning chat_message under this migration's exclusive lock. V64 validates.
ALTER TABLE chat_message
    DROP CONSTRAINT chat_message_sources_valid,
    ADD CONSTRAINT chat_message_sources_valid CHECK (
        jsonb_typeof(sources) = 'array'
        AND octet_length(sources::text) <= 1048576
        AND (role = 'ASSISTANT' OR sources = '[]'::jsonb)
    ) NOT VALID;
