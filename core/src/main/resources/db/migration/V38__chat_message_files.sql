ALTER TABLE chat_message ADD COLUMN files JSONB NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(files) = 'array' AND jsonb_array_length(files) <= 20);
ALTER TABLE chat_command ADD COLUMN file_ids JSONB NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(file_ids) = 'array' AND jsonb_array_length(file_ids) <= 20);
ALTER TABLE persona ADD COLUMN file_ids JSONB NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(file_ids) = 'array' AND jsonb_array_length(file_ids) <= 20
        AND (builtin_key IS NULL OR file_ids = '[]'::jsonb));
ALTER TABLE chat_project ADD COLUMN file_ids JSONB NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(file_ids) = 'array' AND jsonb_array_length(file_ids) <= 20);
ALTER TABLE chat_user_file ADD COLUMN detected_media_type VARCHAR(160);
