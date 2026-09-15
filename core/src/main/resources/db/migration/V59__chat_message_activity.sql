-- The column default already satisfies the constraint; NOT VALID avoids scanning chat_message under this
-- migration's exclusive lock. V60 validates existing rows without blocking writes.
ALTER TABLE chat_message
    ADD COLUMN activity jsonb NOT NULL DEFAULT '{"steps": [], "reasoning": []}'::jsonb,
    ADD CONSTRAINT chat_message_activity_bounded CHECK (
        jsonb_typeof(activity) = 'object'
        AND activity ? 'steps' AND activity ? 'reasoning'
        AND jsonb_typeof(activity -> 'steps') = 'array' AND jsonb_array_length(activity -> 'steps') <= 32
        AND jsonb_typeof(activity -> 'reasoning') = 'array' AND jsonb_array_length(activity -> 'reasoning') <= 32
        AND octet_length(activity::text) <= 131072
        AND (role = 'ASSISTANT' OR activity = '{"steps": [], "reasoning": []}'::jsonb)
    ) NOT VALID;
