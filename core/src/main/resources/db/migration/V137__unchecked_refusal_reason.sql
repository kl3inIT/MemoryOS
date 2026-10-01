-- A guardrail check that cannot run no longer fails the turn. When the Tenant blocks topics and neither the check's
-- task model nor the conversation model returns a verdict, the reply says which topics are restricted and that the
-- question was not answered, and stores why.
ALTER TABLE chat_message DROP CONSTRAINT chat_message_refusal_reason_known;
ALTER TABLE chat_message ADD CONSTRAINT chat_message_refusal_reason_known
    CHECK (refusal_reason IS NULL OR refusal_reason IN ('no_evidence', 'uncited', 'blocked_topic', 'unchecked'));
