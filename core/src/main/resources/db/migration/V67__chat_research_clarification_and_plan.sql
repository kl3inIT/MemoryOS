-- Deep research turn state. As Onyx chat_message.is_clarification, a clarification question makes the next research
-- turn skip clarification. The plan is also stored so a reload shows it, a departure from Onyx.
ALTER TABLE chat_message ADD COLUMN is_clarification BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE chat_message ADD COLUMN research_plan TEXT;

-- Every existing row is NULL, so skipping validation loses nothing and avoids scanning the message table.
ALTER TABLE chat_message ADD CONSTRAINT chat_message_research_plan_bounded
    CHECK (research_plan IS NULL OR char_length(research_plan) BETWEEN 1 AND 100000) NOT VALID;
