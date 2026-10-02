-- MEM-211: a Tenant's model catalog holds only providers an administrator added. Each Tenant was seeded with an
-- "OpenAI" provider whose key was the deployment's own (credential 'deployment'); nothing reads that key any more, so
-- the provider and its models are removed on purpose (owner decision 2026-10-02), in every environment.
--
-- The rows that name those models are cleared first, as deleting them in the catalog would: the Chat default becomes
-- unset, and an Agent's model is cleared as ChatAgentModels does on ModelsRemoved. The task models and the members'
-- own defaults clear through their ON DELETE SET NULL. Chat history keeps its model IDs, which are metadata only.
-- llm_provider.builtin_key stays for now so the previous release still runs on this schema; nothing writes it.
UPDATE chat_model_default d
SET model_configuration_id = NULL, revision = d.revision + 1
FROM model_configuration m
JOIN llm_provider p ON p.tenant_id = m.tenant_id AND p.id = m.provider_id
WHERE d.tenant_id = m.tenant_id AND d.model_configuration_id = m.id AND p.credential = 'deployment';

UPDATE persona a
SET model_configuration_id = NULL, model_revision = a.model_revision + 1, revision = a.revision + 1
FROM model_configuration m
JOIN llm_provider p ON p.tenant_id = m.tenant_id AND p.id = m.provider_id
WHERE a.tenant_id = m.tenant_id AND a.model_configuration_id = m.id AND p.credential = 'deployment';

DELETE FROM llm_provider WHERE credential = 'deployment';
