-- MEM-266: follow-up questions under the latest answer, off until an administrator turns them on.
ALTER TABLE chat_settings ADD COLUMN follow_up_suggestions BOOLEAN NOT NULL DEFAULT FALSE;

-- Writing them is recorded as its own usage flow, so Chat counts answers only. Existing rows satisfy the wider check;
-- NOT VALID avoids scanning the table under this lock, and every new row is checked.
ALTER TABLE ai_usage DROP CONSTRAINT ai_usage_flow_check;
ALTER TABLE ai_usage ADD CONSTRAINT ai_usage_flow_check
    CHECK (flow IN ('CHAT', 'CHAT_NAMING', 'CHAT_GUARDRAIL', 'CHAT_COMPACTION', 'CHAT_FOLLOW_UPS', 'AGENT_BUILDER',
        'DEEP_RESEARCH', 'EMBEDDING_QUERY', 'EMBEDDING_INDEXING', 'IMAGE_GENERATION', 'IMAGE_EDIT', 'SPEECH_TO_TEXT',
        'TEXT_TO_SPEECH', 'MEETING_MINUTES', 'MEETING_CORRECTION', 'OCR')) NOT VALID;
