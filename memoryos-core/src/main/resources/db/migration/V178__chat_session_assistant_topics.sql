-- MEM-273: every web conversation is a topic of its assistant. The assistant's sidebar lists its topics by recency,
-- and the list of assistants reads each one's newest topic; both find the rows by the assistant, which no index
-- led with after the owner.
CREATE INDEX ix_chat_session_persona ON chat_session(tenant_id, owner_actor_id, persona_id, updated_at DESC, id)
    WHERE deleted_at IS NULL AND archived_at IS NULL AND NOT temporary AND surface = 'WEB';
