-- A Chat file references the stored object it is. A browser upload still names its upload, which carries the
-- browser's resume, verification and retirement; a copy the server wrote from a generated file, an image or a
-- meeting's minutes goes through the server-write lifecycle and has no upload at all.
--
-- The object has no foreign key, like thumbnail_stored_object_id (V118): object storage removes stored_objects rows
-- in its own sweeps, an abandoned or retired upload among them, and must not be blocked by Chat. It is recorded when
-- the file's object is adopted and cleared when the file work releases it.
ALTER TABLE chat_user_file
    ADD COLUMN stored_object_id UUID,
    ALTER COLUMN upload_id DROP NOT NULL,
    ADD CONSTRAINT ck_chat_user_file_origin CHECK (upload_id IS NOT NULL OR copied_from_id IS NOT NULL);

UPDATE chat_user_file f SET stored_object_id = u.stored_object_id
FROM object_uploads u
WHERE u.tenant_id = f.tenant_id AND u.id = f.upload_id AND u.stored_object_id IS NOT NULL
    AND f.status NOT IN ('UPLOADING', 'DELETED');

CREATE UNIQUE INDEX ux_chat_user_file_stored_object ON chat_user_file(tenant_id, stored_object_id)
    WHERE stored_object_id IS NOT NULL;
