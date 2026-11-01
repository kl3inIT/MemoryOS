-- MEM-152: a generated file or image can be attached to a new message, a Project or an Agent by copying its
-- bytes into an upload of the same owner. The copy remembers its artifact so asking again returns the same
-- upload instead of a second copy; a deleted copy frees the artifact to be copied again.
ALTER TABLE chat_user_file
    ADD COLUMN copied_from_source VARCHAR(16),
    ADD COLUMN copied_from_id UUID,
    ADD CONSTRAINT ck_chat_user_file_copied_from CHECK (
        (copied_from_source IS NULL AND copied_from_id IS NULL)
        OR (copied_from_source IN ('GENERATED', 'IMAGE') AND copied_from_id IS NOT NULL));

CREATE UNIQUE INDEX ux_chat_user_file_live_copy
    ON chat_user_file(tenant_id, owner_actor_id, copied_from_source, copied_from_id)
    WHERE copied_from_id IS NOT NULL AND status NOT IN ('DELETING', 'DELETED');
