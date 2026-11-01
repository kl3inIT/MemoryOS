-- A Project's conversations are read by recency, and deleting a Project unlinks them; both find the rows by the
-- Project, which no index led with, so they filtered every conversation the owner has.
CREATE INDEX ix_chat_session_project ON chat_session(tenant_id, owner_actor_id, project_id, updated_at DESC, id)
    WHERE project_id IS NOT NULL;

-- The library's name search matches a substring (filename ILIKE '%text%', and an image's prompt), which no btree
-- serves. Trigram indexes on exactly those columns let it skip the owner's other rows. pg_trgm is a trusted
-- extension, so the database owner that runs Flyway may create it.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX ix_chat_user_file_filename_trgm ON chat_user_file USING GIN (filename gin_trgm_ops);
CREATE INDEX ix_chat_file_artifact_filename_trgm ON chat_file_artifact USING GIN (filename gin_trgm_ops);
CREATE INDEX ix_chat_image_artifact_filename_trgm ON chat_image_artifact USING GIN (filename gin_trgm_ops);
CREATE INDEX ix_chat_image_artifact_prompt_trgm ON chat_image_artifact USING GIN (revised_prompt gin_trgm_ops);
