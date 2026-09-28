-- MEM-183: the two marks a person leaves on a meeting for themselves. A star says "this line matters to me" and is
-- left after the meeting, while reading; a bookmark says "come back to this moment" and is left during it, when
-- there is no transcript yet to star. Both belong to the person who made them: a meeting read by five people
-- collects five sets of marks that never overwrite each other.
CREATE TABLE meeting_utterance_star (
    tenant_id UUID NOT NULL,
    meeting_id UUID NOT NULL,
    utterance_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, utterance_id, actor_id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, utterance_id) REFERENCES meeting_utterance (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, actor_id) REFERENCES tenant_memberships (tenant_id, actor_id) ON DELETE CASCADE
);
CREATE INDEX ix_meeting_utterance_star_reader ON meeting_utterance_star (tenant_id, meeting_id, actor_id);

CREATE TABLE meeting_bookmark (
    tenant_id UUID NOT NULL,
    id UUID NOT NULL,
    meeting_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    -- Milliseconds from the start of the recording, the same clock the utterances are stored on.
    at_ms BIGINT NOT NULL CHECK (at_ms >= 0),
    label VARCHAR(200) NOT NULL CHECK (length(label) BETWEEN 1 AND 200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, actor_id) REFERENCES tenant_memberships (tenant_id, actor_id) ON DELETE CASCADE
);
CREATE INDEX ix_meeting_bookmark_reader ON meeting_bookmark (tenant_id, meeting_id, actor_id, at_ms);
