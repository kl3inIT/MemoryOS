-- MEM-280: the Agent Builder runs on a task model of its own, so an administrator can choose one that calls tools
-- well without changing any assistant. As every task does since V121, it starts with the Tenant's Chat model.
ALTER TABLE model_flow_default DROP CONSTRAINT model_flow_default_flow_check;
ALTER TABLE model_flow_default ADD CONSTRAINT model_flow_default_flow_check
    CHECK (flow IN ('CHAT_NAMING', 'MEETING_MINUTES', 'MEETING_CORRECTION', 'CHAT_GUARDRAIL', 'CHAT_COMPACTION',
        'AGENT_BUILDER'));
INSERT INTO model_flow_default(tenant_id, flow, model_configuration_id)
SELECT tenant_id, 'AGENT_BUILDER', model_configuration_id FROM chat_model_default
ON CONFLICT DO NOTHING;

-- A builder turn is recorded as its own usage flow, so Chat counts answers only. Existing rows satisfy the wider
-- check, so NOT VALID avoids scanning the table under this lock; V191 validates.
ALTER TABLE ai_usage DROP CONSTRAINT ai_usage_flow_check;
ALTER TABLE ai_usage ADD CONSTRAINT ai_usage_flow_check
    CHECK (flow IN ('CHAT', 'CHAT_NAMING', 'CHAT_GUARDRAIL', 'CHAT_COMPACTION', 'AGENT_BUILDER', 'DEEP_RESEARCH',
        'EMBEDDING_QUERY', 'EMBEDDING_INDEXING', 'IMAGE_GENERATION', 'IMAGE_EDIT', 'SPEECH_TO_TEXT', 'TEXT_TO_SPEECH',
        'MEETING_MINUTES', 'MEETING_CORRECTION', 'OCR')) NOT VALID;
