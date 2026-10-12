-- MEM-153 phase 1: a conversation can be archived out of the sidebar without being deleted, and a branch
-- records the conversation and message it was made from so its header can link back.
ALTER TABLE chat_session
    ADD COLUMN archived_at TIMESTAMPTZ,
    ADD COLUMN branched_from_session_id UUID,
    ADD COLUMN branched_from_message_id UUID,
    ADD CONSTRAINT ck_chat_session_branch_origin CHECK (
        (branched_from_session_id IS NULL) = (branched_from_message_id IS NULL)),
    ADD CONSTRAINT fk_chat_session_branch_origin FOREIGN KEY (tenant_id, branched_from_session_id)
        REFERENCES chat_session(tenant_id, id);

-- The sidebar lists what is not archived; the archive lists the rest. Both read by recency.
CREATE INDEX chat_session_active ON chat_session(tenant_id, owner_actor_id, updated_at DESC, id)
    WHERE deleted_at IS NULL AND archived_at IS NULL;
CREATE INDEX chat_session_archived ON chat_session(tenant_id, owner_actor_id, archived_at DESC, id)
    WHERE deleted_at IS NULL AND archived_at IS NOT NULL;
