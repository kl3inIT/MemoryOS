-- An answer keeps saying that its image or generated file was deleted, even after the sweep has released the
-- bytes: the row stays as a tombstone with its storage columns emptied instead of being removed.
ALTER TABLE chat_file_artifact
    ALTER COLUMN stored_object_id DROP NOT NULL,
    ALTER COLUMN object_key DROP NOT NULL,
    ADD COLUMN purged_at TIMESTAMPTZ;
ALTER TABLE chat_image_artifact
    ALTER COLUMN stored_object_id DROP NOT NULL,
    ALTER COLUMN object_key DROP NOT NULL,
    ADD COLUMN purged_at TIMESTAMPTZ;

-- A tombstone has no bytes to release and nothing to restore, so the sweep must not claim it again: the
-- claim's index (V90) is replaced by one that leaves tombstones out of it entirely.
DROP INDEX chat_file_artifact_cleanup;
DROP INDEX chat_image_artifact_cleanup;
CREATE INDEX chat_file_artifact_cleanup ON chat_file_artifact(cleanup_until, deleted_at)
    WHERE deleted_at IS NOT NULL AND purged_at IS NULL;
CREATE INDEX chat_image_artifact_cleanup ON chat_image_artifact(cleanup_until, deleted_at)
    WHERE deleted_at IS NOT NULL AND purged_at IS NULL;

-- Bytes released before this change left no row at all, so nothing needs backfilling: what is here still has
-- its bytes. A tombstone from now on carries a size for the card and no storage to charge for.
