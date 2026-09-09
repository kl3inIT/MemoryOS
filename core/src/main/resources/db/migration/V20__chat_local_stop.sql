-- Chat executes in one API process. Stop is a local command; durable outcomes/deadlines remain authoritative.
ALTER TABLE chat_message DROP COLUMN cancel_requested_at;
