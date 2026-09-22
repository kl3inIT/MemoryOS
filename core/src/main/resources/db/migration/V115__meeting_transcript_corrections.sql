-- MEM-183: the owner asks a model to look at the stretches Soniox was unsure of and proposes a fix for each. Nothing
-- is applied until somebody decides, and every change to a line is an event, so the words the provider first wrote
-- are always recoverable.
ALTER TABLE model_flow_default DROP CONSTRAINT model_flow_default_flow_check;
ALTER TABLE model_flow_default ADD CONSTRAINT model_flow_default_flow_check
    CHECK (flow IN ('CHAT_NAMING', 'MEETING_MINUTES', 'MEETING_CORRECTION'));
INSERT INTO model_flow_default(tenant_id, flow) SELECT tenant_id, 'MEETING_CORRECTION' FROM chat_model_default
ON CONFLICT DO NOTHING;

ALTER TABLE ai_usage DROP CONSTRAINT ai_usage_flow_check;
ALTER TABLE ai_usage ADD CONSTRAINT ai_usage_flow_check
    CHECK (flow IN ('CHAT', 'CHAT_NAMING', 'DEEP_RESEARCH', 'EMBEDDING_QUERY', 'EMBEDDING_INDEXING',
        'IMAGE_GENERATION', 'IMAGE_EDIT', 'SPEECH_TO_TEXT', 'TEXT_TO_SPEECH', 'MEETING_MINUTES',
        'MEETING_CORRECTION'));

-- One run at a time per meeting. The owner is watching the request, so this bounds the wait rather than leasing work
-- for a scheduler: a run that outlives its window is abandoned and the next press starts a new one.
ALTER TABLE meeting ADD COLUMN correction_running_until TIMESTAMPTZ;

-- Every change to what an utterance says, whoever made it. This is the only history: meeting_utterance.text is
-- always what the reader sees, and the words the provider first wrote are the `before` of the oldest event.
CREATE TABLE meeting_utterance_event (
    tenant_id UUID NOT NULL,
    id BIGSERIAL,
    meeting_id UUID NOT NULL,
    utterance_id UUID NOT NULL,
    -- The run a change belongs to, so a whole pass can be undone as one.
    run_id UUID,
    actor_id UUID NOT NULL,
    at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- MODEL: a suggestion the owner accepted. HUMAN: the owner's own words. REVERT: putting an earlier text back.
    source VARCHAR(16) NOT NULL CHECK (source IN ('MODEL', 'HUMAN', 'REVERT')),
    before TEXT NOT NULL,
    after TEXT NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, utterance_id) REFERENCES meeting_utterance(tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX ix_meeting_utterance_event_line ON meeting_utterance_event(tenant_id, utterance_id, id);
CREATE INDEX ix_meeting_utterance_event_run ON meeting_utterance_event(tenant_id, run_id, id) WHERE run_id IS NOT NULL;

-- Who last changed the line. Derived from the events above and kept beside the text the read model already loads,
-- both to show and to lock: a line its owner rewrote is never proposed to the model again. NULL means nobody has
-- touched it, which is the honest answer for every line recorded before this migration (Nojoin does the same).
ALTER TABLE meeting_utterance ADD COLUMN edit_source VARCHAR(16)
    CHECK (edit_source IS NULL OR edit_source IN ('MODEL', 'HUMAN'));

-- One proposal for one stretch of one line. It holds the model's own reasons so the owner can judge it, and it
-- survives the decision so a later reader can see what was offered and what was done with it.
CREATE TABLE meeting_correction (
    tenant_id UUID NOT NULL,
    id UUID NOT NULL,
    meeting_id UUID NOT NULL,
    utterance_id UUID NOT NULL,
    run_id UUID NOT NULL,
    -- The stretch this proposal covers, in the coordinates of the text as it was when the run started.
    span_start INT NOT NULL CHECK (span_start >= 0),
    span_end INT NOT NULL CHECK (span_end > span_start),
    before TEXT NOT NULL CHECK (length(before) BETWEEN 1 AND 2000),
    after TEXT NOT NULL CHECK (length(after) BETWEEN 1 AND 2000),
    -- The model's own words on why, shown to the owner rather than trusted.
    reason TEXT NOT NULL DEFAULT '' CHECK (length(reason) <= 1000),
    -- Is this what was said · does it fit the sentence · does it leave the meaning alone.
    confidence REAL NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    context_fit REAL NOT NULL CHECK (context_fit BETWEEN 0 AND 1),
    meaning_safe REAL NOT NULL CHECK (meaning_safe BETWEEN 0 AND 1),
    -- The proposal matched a term the meeting or the Tenant supplied, which is the strongest cheap signal there is.
    matched_glossary BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACCEPTED', 'KEPT', 'REVERTED')),
    decided_at TIMESTAMPTZ,
    decided_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, utterance_id) REFERENCES meeting_utterance(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_meeting_correction_decided CHECK (status = 'PENDING' OR decided_at IS NOT NULL)
);
CREATE INDEX ix_meeting_correction_meeting ON meeting_correction(tenant_id, meeting_id, status, created_at);
CREATE INDEX ix_meeting_correction_run ON meeting_correction(tenant_id, run_id, created_at);
