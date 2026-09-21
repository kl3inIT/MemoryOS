-- MEM-153 phase 2: a deleted conversation waits before it is purged, a conversation can be temporary, and a
-- Tenant can bound how long inactive conversations are kept.

-- The purge claims by how long ago a conversation was deleted, so it needs that order.
CREATE INDEX chat_session_deleted ON chat_session(deleted_at) WHERE deleted_at IS NOT NULL;

-- A temporary conversation leaves no history: it is left out of every listing and deletes itself, with its
-- uploads, a short while after the last message.
ALTER TABLE chat_session ADD COLUMN temporary BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX chat_session_temporary ON chat_session(updated_at)
    WHERE temporary AND deleted_at IS NULL;

-- An upload normally belongs to its owner rather than to one conversation. An upload sent into a temporary
-- conversation belongs to that conversation instead: the library hides it, and the purge releases it.
ALTER TABLE chat_user_file ADD COLUMN temporary_session_id UUID;
CREATE INDEX ix_chat_user_file_temporary ON chat_user_file(temporary_session_id)
    WHERE temporary_session_id IS NOT NULL;

-- The Tenant's retention policy, off unless a number of days is recorded.
ALTER TABLE chat_settings ADD COLUMN chat_retention_days INTEGER
    CHECK (chat_retention_days IS NULL OR chat_retention_days BETWEEN 1 AND 3650);
