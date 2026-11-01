-- The recordings a meeting still holds after it has been transcribed or given up on. Every recording-job pass reads
-- them to retire their bytes, so the sweep reads this small index instead of every meeting of every Tenant.
CREATE INDEX ix_meeting_audio_held ON meeting(updated_at, id)
    WHERE audio_upload_id IS NOT NULL AND audio_status IN ('DONE', 'FAILED');
