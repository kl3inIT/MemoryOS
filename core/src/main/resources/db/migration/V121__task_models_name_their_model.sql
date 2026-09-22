-- A task model left empty meant "whatever the conversation model is", which the administration page could only
-- describe as a default: it never said which model actually wrote the minutes or proposed the corrections. Every
-- empty task model now names the Tenant's current Chat model, so what the page shows is what runs, and changing the
-- Chat model no longer changes the tasks behind the administrator's back.
--
-- A task model can still become empty afterwards: deleting its model clears it (ON DELETE SET NULL). That case keeps
-- falling back to the Chat model, and the page names that model too.
UPDATE model_flow_default flow
SET model_configuration_id = chat.model_configuration_id, revision = flow.revision + 1
FROM chat_model_default chat
WHERE flow.tenant_id = chat.tenant_id AND flow.model_configuration_id IS NULL;
