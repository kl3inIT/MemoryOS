-- MEM-92: generated minutes for a meeting — one summary, its decisions and its action items, each traceable to the
-- utterances it came from. The job runs where the chat model catalog lives, so it carries its own lease and attempts.
ALTER TABLE model_flow_default DROP CONSTRAINT model_flow_default_flow_check;
ALTER TABLE model_flow_default ADD CONSTRAINT model_flow_default_flow_check
    CHECK (flow IN ('CHAT_NAMING', 'MEETING_MINUTES'));
INSERT INTO model_flow_default(tenant_id, flow) SELECT tenant_id, 'MEETING_MINUTES' FROM chat_model_default
ON CONFLICT DO NOTHING;

ALTER TABLE ai_usage DROP CONSTRAINT ai_usage_flow_check;
ALTER TABLE ai_usage ADD CONSTRAINT ai_usage_flow_check
    CHECK (flow IN ('CHAT', 'CHAT_NAMING', 'DEEP_RESEARCH', 'EMBEDDING_QUERY', 'EMBEDDING_INDEXING',
        'IMAGE_GENERATION', 'IMAGE_EDIT', 'SPEECH_TO_TEXT', 'TEXT_TO_SPEECH', 'MEETING_MINUTES'));

ALTER TABLE meeting
    ADD COLUMN minutes_status VARCHAR(16) NOT NULL DEFAULT 'NONE'
        CHECK (minutes_status IN ('NONE', 'PENDING', 'RUNNING', 'READY', 'FAILED')),
    ADD COLUMN minutes_attempts INT NOT NULL DEFAULT 0 CHECK (minutes_attempts >= 0),
    ADD COLUMN minutes_lease_until TIMESTAMPTZ,
    ADD COLUMN minutes_failure VARCHAR(64),
    -- What the model made of the meeting, written once per run.
    ADD COLUMN minutes_summary TEXT NOT NULL DEFAULT '' CHECK (length(minutes_summary) <= 20000),
    ADD COLUMN minutes_kind VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN minutes_generated_at TIMESTAMPTZ;
-- One meeting at a time per API replica; the oldest pending run wins.
CREATE INDEX ix_meeting_minutes_pending ON meeting(minutes_status, minutes_lease_until, ended_at)
    WHERE minutes_status IN ('PENDING', 'RUNNING');

-- A decision the meeting reached, or a task it handed out; both cite the utterance that carries them.
CREATE TABLE meeting_minutes_item (
    tenant_id UUID NOT NULL,
    id UUID NOT NULL,
    meeting_id UUID NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('DECISION', 'ACTION')),
    position INT NOT NULL CHECK (position >= 0),
    text TEXT NOT NULL CHECK (length(text) BETWEEN 1 AND 2000),
    -- Actions name one owner and may name a deadline, both as the meeting said them.
    owner VARCHAR(200),
    due VARCHAR(100),
    -- The sentence the item rests on, quoted from the transcript, and the utterance it came from.
    quote TEXT CHECK (quote IS NULL OR length(quote) <= 2000),
    source_utterance_id UUID,
    done BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, source_utterance_id) REFERENCES meeting_utterance(tenant_id, id) ON DELETE SET NULL,
    CONSTRAINT ck_meeting_minutes_item_owner CHECK (kind = 'ACTION' OR (owner IS NULL AND due IS NULL))
);
CREATE INDEX ix_meeting_minutes_item_meeting ON meeting_minutes_item(tenant_id, meeting_id, kind, position);
