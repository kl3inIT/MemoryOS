-- MEM-109: record where an edited image came from. Provenance only, not authority: the source may be
-- deleted later, so these columns carry no foreign key. At most one source kind is set.
ALTER TABLE chat_image_artifact
    ADD COLUMN source_artifact_id UUID,
    ADD COLUMN source_file_id UUID,
    ADD CONSTRAINT ck_chat_image_artifact_single_source
        CHECK (source_artifact_id IS NULL OR source_file_id IS NULL);
