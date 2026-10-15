-- MEM-268: a summary of a conversation's earlier turns, which a turn replays in their place.

-- One row a compaction. parent_message_id is the last USER message the summary was written after and
-- cutoff_message_id the last message it covers; a turn uses the summary whose parent is its nearest ancestor and
-- whose cutoff is on its branch, so an edit or a regeneration above the cutoff ignores it. Rows are never updated: a
-- later compaction writes a new row that rolls the previous summary forward.
CREATE TABLE chat_session_summary (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    session_id UUID NOT NULL,
    parent_message_id UUID NOT NULL,
    cutoff_message_id UUID NOT NULL,
    content TEXT NOT NULL,
    token_count INTEGER NOT NULL,
    model_configuration_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chat_session_summary_session FOREIGN KEY (tenant_id, session_id)
        REFERENCES chat_session (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_session_summary_parent FOREIGN KEY (session_id, parent_message_id)
        REFERENCES chat_message (session_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_session_summary_cutoff FOREIGN KEY (session_id, cutoff_message_id)
        REFERENCES chat_message (session_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_session_summary_model FOREIGN KEY (tenant_id, model_configuration_id)
        REFERENCES model_configuration (tenant_id, id) ON DELETE SET NULL (model_configuration_id),
    CONSTRAINT ck_chat_session_summary_tokens CHECK (token_count >= 0)
);

-- A turn looks its summary up by the ancestors of its question; the newest row of a parent wins.
CREATE INDEX ix_chat_session_summary_parent ON chat_session_summary (session_id, parent_message_id, created_at DESC);

-- Whether a session is being compacted, and until when a failed compaction is not tried again. One row a session,
-- kept apart from chat_session so that a claim never locks the row every turn updates, and apart from the summaries,
-- which are per branch.
CREATE TABLE chat_session_compaction (
    session_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    claimed_until TIMESTAMPTZ,
    retry_after TIMESTAMPTZ,
    CONSTRAINT fk_chat_session_compaction_session FOREIGN KEY (tenant_id, session_id)
        REFERENCES chat_session (tenant_id, id) ON DELETE CASCADE
);
