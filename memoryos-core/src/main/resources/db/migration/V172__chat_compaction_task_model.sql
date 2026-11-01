-- MEM-268: the summary that stands in for a long conversation's earlier turns is written by a task model of its own,
-- so an administrator can choose a cheaper one. As every task does since V121, it starts with the Tenant's Chat model.
ALTER TABLE model_flow_default DROP CONSTRAINT model_flow_default_flow_check;
ALTER TABLE model_flow_default ADD CONSTRAINT model_flow_default_flow_check
    CHECK (flow IN ('CHAT_NAMING', 'MEETING_MINUTES', 'MEETING_CORRECTION', 'CHAT_GUARDRAIL', 'CHAT_COMPACTION'));
INSERT INTO model_flow_default(tenant_id, flow, model_configuration_id)
SELECT tenant_id, 'CHAT_COMPACTION', model_configuration_id FROM chat_model_default
ON CONFLICT DO NOTHING;

-- The summary's model call is recorded as its own usage flow, as Onyx records CHAT_HISTORY_SUMMARIZATION, so Chat
-- counts each turn once. Existing rows satisfy the wider check, so NOT VALID avoids scanning the table under this
-- lock; V173 validates.
ALTER TABLE ai_usage DROP CONSTRAINT ai_usage_flow_check;
ALTER TABLE ai_usage ADD CONSTRAINT ai_usage_flow_check
    CHECK (flow IN ('CHAT', 'CHAT_NAMING', 'CHAT_GUARDRAIL', 'CHAT_COMPACTION', 'DEEP_RESEARCH', 'EMBEDDING_QUERY',
        'EMBEDDING_INDEXING', 'IMAGE_GENERATION', 'IMAGE_EDIT', 'SPEECH_TO_TEXT', 'TEXT_TO_SPEECH', 'MEETING_MINUTES',
        'MEETING_CORRECTION', 'OCR')) NOT VALID;
