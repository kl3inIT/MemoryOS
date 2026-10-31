-- MEM-188: the topics a meeting moved through, each pointing at the line where it began, so a two-hour transcript
-- reads with a table of contents. They are written in the same model call as the rest of the minutes and stored beside
-- the decisions and the work, because they are the same kind of thing: a short text resting on one utterance.
-- Existing rows satisfy the wider check, so NOT VALID avoids scanning the table under this lock. V120 validates.
ALTER TABLE meeting_minutes_item DROP CONSTRAINT meeting_minutes_item_kind_check;
ALTER TABLE meeting_minutes_item ADD CONSTRAINT meeting_minutes_item_kind_check
    CHECK (kind IN ('DECISION', 'ACTION', 'TOPIC')) NOT VALID;
