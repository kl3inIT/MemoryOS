-- The file library shows a generated image in a cell a few hundred pixels wide but was served the full
-- artifact, so a page of fifty images cost tens of megabytes. An image now keeps a small derived rendering
-- next to it, written the first time the library asks for one. The columns mirror the converted-PDF preview
-- of a generated file (V73), so the deletion sweep releases both objects with the code it already has.

ALTER TABLE chat_image_artifact
    ADD COLUMN thumbnail_stored_object_id UUID,
    ADD COLUMN thumbnail_object_key VARCHAR(240),
    ADD COLUMN thumbnail_media_type VARCHAR(100),
    ADD CONSTRAINT ck_chat_image_artifact_thumbnail CHECK (
        (thumbnail_stored_object_id IS NULL AND thumbnail_object_key IS NULL AND thumbnail_media_type IS NULL)
        OR (thumbnail_stored_object_id IS NOT NULL AND thumbnail_object_key IS NOT NULL
            AND thumbnail_media_type IS NOT NULL));
