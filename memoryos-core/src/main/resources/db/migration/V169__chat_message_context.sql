-- MEM-267: what a reply's last model call carried against the model's input limit, for the composer's context gauge.
ALTER TABLE chat_message
    ADD COLUMN context_tokens INTEGER CHECK (context_tokens >= 0),
    ADD COLUMN context_limit INTEGER CHECK (context_limit > 0);
