-- MEM-169: a meeting's transcript is recorded here, read from an uploaded recording, or written by the platform the
-- meeting was held on. A transcript a platform wrote is read by the people who attended.
ALTER TABLE meeting
    ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'RECORDED'
        CHECK (origin IN ('RECORDED', 'UPLOADED', 'GOOGLE_MEET'));
UPDATE meeting SET origin = 'UPLOADED' WHERE audio_status <> 'NONE';

-- A platform names every voice itself, so its lines belong to neither the owner's microphone nor a shared tab.
ALTER TABLE meeting_speaker DROP CONSTRAINT meeting_speaker_track_check;
ALTER TABLE meeting_speaker ADD CONSTRAINT meeting_speaker_track_check
    CHECK (track IN ('MIC', 'TAB', 'IMPORTED'));
ALTER TABLE meeting_utterance DROP CONSTRAINT meeting_utterance_track_check;
ALTER TABLE meeting_utterance ADD CONSTRAINT meeting_utterance_track_check
    CHECK (track IN ('MIC', 'TAB', 'IMPORTED'));

-- Who the platform says was in the meeting, by the address they signed in with. A reader matches by verified login
-- email at read time (ADR 0011), so nobody is admitted or provisioned here.
CREATE TABLE meeting_attendee (
    tenant_id UUID NOT NULL,
    meeting_id UUID NOT NULL,
    email VARCHAR(320) NOT NULL CHECK (email = lower(email) AND length(email) BETWEEN 3 AND 320),
    PRIMARY KEY (tenant_id, meeting_id, email),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting(tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX ix_meeting_attendee_email ON meeting_attendee(tenant_id, email, meeting_id);

-- One row per platform conference ever looked at. It outlives its meeting: an owner who deletes an imported meeting
-- leaves the row, so the next run does not bring the meeting back.
CREATE TABLE meeting_external_record (
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    origin VARCHAR(16) NOT NULL CHECK (origin IN ('GOOGLE_MEET')),
    external_id VARCHAR(512) NOT NULL CHECK (length(external_id) >= 1),
    meeting_id UUID,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, origin, external_id),
    FOREIGN KEY (tenant_id, meeting_id) REFERENCES meeting(tenant_id, id) ON DELETE SET NULL (meeting_id)
);
