-- MEM-225: a model declares whether its deployment holds an answer to a JSON schema sent with the request. Models
-- saved before the capability existed did not declare it.
UPDATE model_configuration
SET settings = jsonb_set(settings, '{capabilities,structuredOutput}', 'false'::jsonb)
WHERE jsonb_typeof(settings -> 'capabilities') = 'object'
  AND settings -> 'capabilities' -> 'structuredOutput' IS NULL;
