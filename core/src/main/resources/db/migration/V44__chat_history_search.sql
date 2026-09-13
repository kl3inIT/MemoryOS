-- Explicit simple configuration preserves Vietnamese/English tokens without English stemming.
CREATE INDEX ix_chat_session_search ON chat_session
    USING GIN (to_tsvector('simple', title)) WHERE deleted_at IS NULL;
CREATE INDEX ix_chat_message_search ON chat_message
    USING GIN (to_tsvector('simple', CASE WHEN octet_length(content) <= 65536 THEN content ELSE '' END))
    WHERE role IN ('USER', 'ASSISTANT') AND octet_length(content) <= 65536;
-- Larger saved answers remain searchable through the same token parser at read time.
-- Do not let PostgreSQL's tsvector size limit reject an otherwise valid Chat write.
