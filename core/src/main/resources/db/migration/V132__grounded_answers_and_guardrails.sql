-- MEM-195: answers only from the Tenant's documents, and the sensitive-topic guardrails.
ALTER TABLE chat_settings ADD COLUMN grounded_answers BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE chat_settings ADD COLUMN grounded_allow_web BOOLEAN NOT NULL DEFAULT FALSE;
-- [{"topic":"LEADERS","enabled":true,"message":"..."}]: only the built-in topics; their descriptions live in code.
ALTER TABLE chat_settings ADD COLUMN guardrail_topics JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE chat_settings ADD COLUMN blocked_phrases JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE chat_settings ADD COLUMN blocked_phrase_message TEXT;
ALTER TABLE chat_settings ADD CONSTRAINT chat_settings_blocked_phrases_bounded
    CHECK (jsonb_typeof(blocked_phrases) = 'array' AND jsonb_array_length(blocked_phrases) <= 20);

ALTER TABLE persona ADD COLUMN grounded BOOLEAN NOT NULL DEFAULT FALSE;

-- Why a completed answer is a refusal rather than an answer: no_evidence, uncited or blocked_topic.
ALTER TABLE chat_message ADD COLUMN refusal_reason VARCHAR(16);
ALTER TABLE chat_message ADD CONSTRAINT chat_message_refusal_reason_known
    CHECK (refusal_reason IS NULL OR refusal_reason IN ('no_evidence', 'uncited', 'blocked_topic'));
