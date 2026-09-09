ALTER TABLE chat_message
    ADD COLUMN cancel_requested_at TIMESTAMPTZ,
    ADD COLUMN model_name VARCHAR(200),
    ADD COLUMN input_tokens BIGINT CHECK (input_tokens >= 0),
    ADD COLUMN output_tokens BIGINT CHECK (output_tokens >= 0),
    ADD COLUMN cost_usd DOUBLE PRECISION CHECK (cost_usd >= 0);

CREATE INDEX ix_chat_running_deadline ON chat_message(deadline_at) WHERE status = 'RUNNING';
