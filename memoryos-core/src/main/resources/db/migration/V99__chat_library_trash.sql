-- MEM-152 phase 4: deleting a file moves it to the trash. The row is hidden at once, as before, but its bytes
-- are released only after the trash window, so the owner can restore it until then (ADR 0014).
ALTER TABLE chat_user_file
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN purge_after TIMESTAMPTZ;

ALTER TABLE chat_file_artifact ADD COLUMN purge_after TIMESTAMPTZ;
ALTER TABLE chat_image_artifact ADD COLUMN purge_after TIMESTAMPTZ;

-- Whatever was already deleted keeps the behaviour it was deleted under: released as soon as a sweep sees it.
UPDATE chat_user_file SET deleted_at = updated_at, purge_after = updated_at WHERE status IN ('DELETING', 'DELETED');
UPDATE chat_file_artifact SET purge_after = deleted_at WHERE deleted_at IS NOT NULL;
UPDATE chat_image_artifact SET purge_after = deleted_at WHERE deleted_at IS NOT NULL;

CREATE INDEX chat_user_file_trash ON chat_user_file(tenant_id, owner_actor_id, deleted_at DESC)
    WHERE deleted_at IS NOT NULL;
CREATE INDEX chat_user_file_purge ON chat_user_file(purge_after)
    WHERE status = 'DELETING' AND purge_after IS NOT NULL;
