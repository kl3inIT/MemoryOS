-- Archive hides a conversation from the regular list and history search; it stays readable and writable.
ALTER TABLE chat_session ADD COLUMN archived_at TIMESTAMPTZ;
CREATE INDEX ix_chat_session_owner_regular ON chat_session(tenant_id, owner_actor_id, updated_at DESC, id)
    WHERE deleted_at IS NULL AND archived_at IS NULL;
