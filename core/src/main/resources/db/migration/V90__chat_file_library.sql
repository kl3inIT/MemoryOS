-- MEM-142: the owner's file library lists uploads, run_python files and generated images together,
-- and an artifact can now be deleted. Deletion hides the row at once; a worker sweep releases the bytes.

ALTER TABLE chat_file_artifact
    ADD COLUMN owner_actor_id UUID,
    ADD COLUMN session_id UUID,
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN cleanup_token UUID,
    ADD COLUMN cleanup_until TIMESTAMPTZ;

ALTER TABLE chat_image_artifact
    ADD COLUMN owner_actor_id UUID,
    ADD COLUMN session_id UUID,
    ADD COLUMN filename VARCHAR(200),
    ADD COLUMN size_bytes BIGINT CHECK (size_bytes IS NULL OR size_bytes >= 0),
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN cleanup_token UUID,
    ADD COLUMN cleanup_until TIMESTAMPTZ;

-- The answer that produced an artifact identifies its owner and conversation; listing reads them directly
-- so a page never joins and sorts two tables per source.
UPDATE chat_file_artifact a
SET owner_actor_id = s.owner_actor_id, session_id = s.id
FROM chat_message m JOIN chat_session s ON s.id = m.session_id
WHERE m.id = a.message_id;

UPDATE chat_image_artifact a
SET owner_actor_id = s.owner_actor_id, session_id = s.id
FROM chat_message m JOIN chat_session s ON s.id = m.session_id
WHERE m.id = a.message_id;

-- A generated image had no name or size. Name it after its creation time, as the library shows it.
UPDATE chat_image_artifact a
SET filename = 'image-' || to_char(a.created_at AT TIME ZONE 'UTC', 'YYYYMMDD-HH24MISS') || '-'
        || substr(replace(a.id::text, '-', ''), 1, 8) || '.'
        || CASE a.media_type WHEN 'image/jpeg' THEN 'jpg' WHEN 'image/webp' THEN 'webp' ELSE 'png' END,
    size_bytes = COALESCE((SELECT o.size_bytes FROM stored_objects o
        WHERE o.tenant_id = a.tenant_id AND o.id = a.stored_object_id), 0);

-- An artifact whose answer row is gone already has no route to reach it: every read joins the message.
-- Its bytes stay where they are; releasing them is not this migration's business.
DELETE FROM chat_file_artifact WHERE owner_actor_id IS NULL;
DELETE FROM chat_image_artifact WHERE owner_actor_id IS NULL;

ALTER TABLE chat_file_artifact
    ALTER COLUMN owner_actor_id SET NOT NULL,
    ALTER COLUMN session_id SET NOT NULL;

ALTER TABLE chat_image_artifact
    ALTER COLUMN owner_actor_id SET NOT NULL,
    ALTER COLUMN session_id SET NOT NULL,
    ALTER COLUMN filename SET NOT NULL,
    ALTER COLUMN size_bytes SET NOT NULL;

CREATE INDEX chat_file_artifact_owner ON chat_file_artifact(tenant_id, owner_actor_id, created_at DESC, id)
    WHERE deleted_at IS NULL;
CREATE INDEX chat_image_artifact_owner ON chat_image_artifact(tenant_id, owner_actor_id, created_at DESC, id)
    WHERE deleted_at IS NULL;
CREATE INDEX chat_file_artifact_cleanup ON chat_file_artifact(cleanup_until, deleted_at)
    WHERE deleted_at IS NOT NULL;
CREATE INDEX chat_image_artifact_cleanup ON chat_image_artifact(cleanup_until, deleted_at)
    WHERE deleted_at IS NOT NULL;
