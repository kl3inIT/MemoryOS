-- MEM-183: the stretches of an utterance the provider was least sure of, as [{"start":0,"end":3,"confidence":0.47}]
-- in the coordinates of the stored text. Utterances written before this migration keep an empty list: only the
-- provider knows its per-token confidence, and it is not asked again.
ALTER TABLE meeting_utterance ADD COLUMN spans JSONB NOT NULL DEFAULT '[]'::jsonb;
