-- MEM-147: each member's starting creativity and reasoning level, and the level pinned on one conversation.
-- Onyx keeps the same pair: user defaults plus a per-session override.
ALTER TABLE chat_preferences
    ADD COLUMN temperature_default DOUBLE PRECISION,
    ADD COLUMN reasoning_effort_default VARCHAR(16),
    ADD CONSTRAINT ck_chat_preferences_temperature
        CHECK (temperature_default IS NULL OR (temperature_default >= 0 AND temperature_default <= 2)),
    ADD CONSTRAINT ck_chat_preferences_reasoning
        CHECK (reasoning_effort_default IS NULL OR reasoning_effort_default IN ('OFF', 'LOW', 'MEDIUM', 'HIGH'));

ALTER TABLE chat_session
    ADD COLUMN reasoning_effort VARCHAR(16),
    ADD CONSTRAINT ck_chat_session_reasoning
        CHECK (reasoning_effort IS NULL OR reasoning_effort IN ('OFF', 'LOW', 'MEDIUM', 'HIGH'));
