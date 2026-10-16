-- MEM-186: the owner dismissed the name this voice gave itself, so the transcript stops offering it again.
ALTER TABLE meeting_speaker ADD COLUMN suggestion_dismissed boolean NOT NULL DEFAULT false;
