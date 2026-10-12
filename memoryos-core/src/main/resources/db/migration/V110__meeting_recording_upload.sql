-- MEM-92: a meeting made from an uploaded recording. The audio is held only until its transcription finishes or
-- gives up, then the stored object is retired; nothing here keeps the recording.
ALTER TABLE meeting DROP CONSTRAINT meeting_status_check;
ALTER TABLE meeting ADD CONSTRAINT meeting_status_check
    CHECK (status IN ('RECORDING', 'TRANSCRIBING', 'ENDED'));

ALTER TABLE meeting
    ADD COLUMN audio_upload_id UUID,
    ADD COLUMN audio_key VARCHAR(512),
    ADD COLUMN audio_filename VARCHAR(255),
    ADD COLUMN audio_media_type VARCHAR(160),
    ADD COLUMN audio_size_bytes BIGINT CHECK (audio_size_bytes IS NULL OR audio_size_bytes > 0),
    -- The connection the owner chose for this recording; null uses the Tenant's selected one.
    ADD COLUMN audio_provider VARCHAR(32),
    ADD COLUMN audio_status VARCHAR(16) NOT NULL DEFAULT 'NONE'
        CHECK (audio_status IN ('NONE', 'WAITING', 'PENDING', 'RUNNING', 'DONE', 'FAILED')),
    ADD COLUMN audio_attempts INT NOT NULL DEFAULT 0 CHECK (audio_attempts >= 0),
    ADD COLUMN audio_lease_until TIMESTAMPTZ,
    ADD COLUMN audio_failure VARCHAR(64);

-- One recording at a time per API replica; the oldest waiting upload wins.
CREATE INDEX ix_meeting_audio_pending ON meeting(audio_status, audio_lease_until, created_at)
    WHERE audio_status IN ('PENDING', 'RUNNING');

-- A recording is a new kind of stored object: larger than a chat file, and held only until it is transcribed.
ALTER TABLE stored_objects DROP CONSTRAINT ck_stored_objects_size;
ALTER TABLE stored_objects ADD CONSTRAINT ck_stored_objects_size CHECK (
    (input_kind = 'BINARY' AND size_bytes BETWEEN 1 AND 104857600)
    OR (input_kind = 'NATIVE_SNAPSHOT' AND size_bytes BETWEEN 1 AND 33554432)
    OR (input_kind = 'CHAT_FILE' AND size_bytes BETWEEN 1 AND 262144000)
    OR (input_kind = 'MEETING_AUDIO' AND size_bytes BETWEEN 1 AND 524288000)
);
ALTER TABLE object_uploads DROP CONSTRAINT ck_object_uploads_input_kind;
ALTER TABLE object_uploads ADD CONSTRAINT ck_object_uploads_input_kind
    CHECK (input_kind IN ('BINARY', 'CHAT_FILE', 'MEETING_AUDIO'));
