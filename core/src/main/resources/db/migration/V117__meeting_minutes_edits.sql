-- MEM-188: the minutes are the owner's to correct. A model that misheard one conclusion should cost one edit, not a
-- rerun of the whole meeting, and a biên bản that gets signed has to say which words were written by whom.
--
-- The same rule the transcript follows: what the reader sees is the row, and the history is the events beside it.
-- There is no second column holding the model's first version, because two places holding it is two places to drift.
CREATE TABLE meeting_minutes_event (
    tenant_id UUID NOT NULL,
    id BIGSERIAL,
    meeting_id UUID NOT NULL,
    -- Null for the summary, which belongs to the meeting rather than to one item.
    item_id UUID,
    field VARCHAR(16) NOT NULL CHECK (field IN ('SUMMARY', 'TEXT', 'OWNER', 'DUE')),
    actor_id UUID NOT NULL,
    at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    before TEXT NOT NULL,
    after TEXT NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX ix_meeting_minutes_event_meeting ON meeting_minutes_event (tenant_id, meeting_id, id);

-- Whether the words standing now are the owner's rather than the model's. Derived from the events above, kept beside
-- what the read model already loads, and what makes a rerun ask before it throws the owner's work away.
ALTER TABLE meeting ADD COLUMN minutes_edited BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE meeting_minutes_item ADD COLUMN edited BOOLEAN NOT NULL DEFAULT FALSE;
