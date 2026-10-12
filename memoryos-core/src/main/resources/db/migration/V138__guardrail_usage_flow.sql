-- The guardrail check before an answer is recorded as its own usage flow, so Chat counts each turn once.
-- Existing rows satisfy the wider check, so NOT VALID avoids scanning the table under this lock. V139 validates.
ALTER TABLE ai_usage DROP CONSTRAINT ai_usage_flow_check;
ALTER TABLE ai_usage ADD CONSTRAINT ai_usage_flow_check
    CHECK (flow IN ('CHAT', 'CHAT_NAMING', 'CHAT_GUARDRAIL', 'DEEP_RESEARCH', 'EMBEDDING_QUERY', 'EMBEDDING_INDEXING',
        'IMAGE_GENERATION', 'IMAGE_EDIT', 'SPEECH_TO_TEXT', 'TEXT_TO_SPEECH', 'MEETING_MINUTES',
        'MEETING_CORRECTION')) NOT VALID;
