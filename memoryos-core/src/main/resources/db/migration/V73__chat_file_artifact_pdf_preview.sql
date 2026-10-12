-- A PDF rendering of a generated presentation, converted in the interpreter executor on first preview (MEM-111).
ALTER TABLE chat_file_artifact
    ADD COLUMN preview_stored_object_id UUID,
    ADD COLUMN preview_object_key VARCHAR(240),
    ADD COLUMN preview_size_bytes BIGINT CHECK (preview_size_bytes >= 0),
    ADD CONSTRAINT chat_file_artifact_preview_complete CHECK (
        (preview_stored_object_id IS NULL AND preview_object_key IS NULL AND preview_size_bytes IS NULL)
        OR (preview_stored_object_id IS NOT NULL AND preview_object_key IS NOT NULL AND preview_size_bytes IS NOT NULL));
