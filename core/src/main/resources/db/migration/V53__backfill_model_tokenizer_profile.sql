UPDATE model_configuration
SET settings = jsonb_set(settings, '{tokenizerProfile}', '"openai-o200k-v1"'::jsonb)
WHERE NOT (settings ? 'tokenizerProfile');
