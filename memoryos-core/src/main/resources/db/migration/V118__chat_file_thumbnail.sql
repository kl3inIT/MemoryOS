-- An uploaded image was listed in the file library with a generic file icon, because only a generated image
-- had a small derived rendering (V106). An upload now keeps one next to it as well, written the first time the
-- library asks for one, so a page of photographs costs thumbnails rather than originals. The columns are the
-- generated image's, so the byte-releasing work releases this object with the code it already has.

ALTER TABLE chat_user_file
    ADD COLUMN thumbnail_stored_object_id UUID,
    ADD COLUMN thumbnail_object_key VARCHAR(240),
    ADD COLUMN thumbnail_media_type VARCHAR(100),
    ADD CONSTRAINT ck_chat_user_file_thumbnail CHECK (
        (thumbnail_stored_object_id IS NULL AND thumbnail_object_key IS NULL AND thumbnail_media_type IS NULL)
        OR (thumbnail_stored_object_id IS NOT NULL AND thumbnail_object_key IS NOT NULL
            AND thumbnail_media_type IS NOT NULL));
