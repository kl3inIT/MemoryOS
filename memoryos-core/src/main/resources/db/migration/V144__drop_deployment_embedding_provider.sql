-- Embedding keys are catalog entries (MEM-216): no provider refers to the deployment's key any more. The provider the
-- first start seeded from deployment configuration goes when no search generation uses it, as on staging and
-- production; one still in use, such as a development database seeded against OpenAI, keeps its row and asks for a key.
DELETE FROM embedding_provider p
WHERE p.credential = 'deployment'
  AND NOT EXISTS (SELECT 1 FROM search_settings s WHERE s.tenant_id = p.tenant_id AND s.provider_id = p.id);

UPDATE embedding_provider SET credential = NULL, revision = revision + 1 WHERE credential = 'deployment';
