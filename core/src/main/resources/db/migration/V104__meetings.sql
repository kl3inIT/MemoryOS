-- MEM-92: owner-private meetings. A meeting keeps its finalized utterances and speaker names; audio is never stored.
CREATE TABLE meeting (
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    id UUID NOT NULL,
    owner_actor_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL CHECK (title = trim(title) AND length(title) BETWEEN 1 AND 200 AND title !~ '[[:cntrl:]]'),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('ONLINE', 'IN_PERSON')),
    language VARCHAR(8) CHECK (language IN ('vi', 'en')),
    -- Participant names and domain terms entered before recording; JSON arrays of strings.
    participants JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(participants) = 'array'),
    terms JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(terms) = 'array'),
    notes TEXT NOT NULL DEFAULT '' CHECK (length(notes) <= 50000),
    status VARCHAR(16) NOT NULL DEFAULT 'RECORDING' CHECK (status IN ('RECORDING', 'ENDED')),
    -- Provider and model of the last stream, and whether its speaker labels distinguish people.
    provider VARCHAR(32),
    model VARCHAR(200),
    diarized BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, owner_actor_id) REFERENCES tenant_memberships(tenant_id, actor_id),
    CONSTRAINT ck_meeting_ended CHECK ((status = 'ENDED') = (ended_at IS NOT NULL))
);
CREATE INDEX ix_meeting_owner ON meeting(tenant_id, owner_actor_id, created_at DESC, id);

-- One diarized voice per track; MIC is the owner in an online meeting and the whole room in person.
CREATE TABLE meeting_speaker (
    tenant_id UUID NOT NULL,
    meeting_id UUID NOT NULL,
    track VARCHAR(8) NOT NULL CHECK (track IN ('MIC', 'TAB')),
    label VARCHAR(16) NOT NULL CHECK (label ~ '^[0-9A-Za-z_-]{1,16}$'),
    name VARCHAR(200) CHECK (name IS NULL OR (name = trim(name) AND length(name) BETWEEN 1 AND 200 AND name !~ '[[:cntrl:]]')),
    PRIMARY KEY (tenant_id, meeting_id, track, label),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting(tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE meeting_utterance (
    tenant_id UUID NOT NULL,
    id UUID NOT NULL,
    meeting_id UUID NOT NULL,
    track VARCHAR(8) NOT NULL CHECK (track IN ('MIC', 'TAB')),
    speaker VARCHAR(16) NOT NULL,
    start_ms BIGINT NOT NULL CHECK (start_ms >= 0),
    end_ms BIGINT NOT NULL CHECK (end_ms >= start_ms),
    text TEXT NOT NULL CHECK (length(text) BETWEEN 1 AND 20000),
    confidence REAL NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, meeting_id, track, speaker) REFERENCES meeting_speaker(tenant_id, meeting_id, track, label)
);
CREATE INDEX ix_meeting_utterance_time ON meeting_utterance(tenant_id, meeting_id, start_ms, id);
