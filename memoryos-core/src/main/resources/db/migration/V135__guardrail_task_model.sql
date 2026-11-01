-- The guardrail check of a grounded or guarded turn asks for a structured verdict. A model that answers well can
-- still return that verdict in the wrong shape (staging, 2026-10-01), and the check fails the whole turn closed, so
-- the check becomes a task with its own model. As every task does since V121, it starts with the Tenant's Chat
-- model, which is what most conversations already used for it.
ALTER TABLE model_flow_default DROP CONSTRAINT model_flow_default_flow_check;
ALTER TABLE model_flow_default ADD CONSTRAINT model_flow_default_flow_check
    CHECK (flow IN ('CHAT_NAMING', 'MEETING_MINUTES', 'MEETING_CORRECTION', 'CHAT_GUARDRAIL'));
INSERT INTO model_flow_default(tenant_id, flow, model_configuration_id)
SELECT tenant_id, 'CHAT_GUARDRAIL', model_configuration_id FROM chat_model_default
ON CONFLICT DO NOTHING;
