-- An agent's model is the catalog entry model_configuration_id names. The model name string from before the catalog
-- (V18) was still written from memoryos.chat.persona.model on every new agent and read by nothing.
ALTER TABLE persona DROP COLUMN model;
