-- MEM-301: an action's owner may be a voice of the meeting. The item points at the voice and carries no text, so the
-- name is read from the voice whenever the item is shown, and naming the voice names every action it owns.
ALTER TABLE meeting_minutes_item
    ADD COLUMN owner_track VARCHAR(8),
    ADD COLUMN owner_label VARCHAR(16),
    ADD CONSTRAINT ck_meeting_minutes_item_owner_speaker CHECK (
        (owner_track IS NULL AND owner_label IS NULL)
        OR (owner_track IS NOT NULL AND owner_label IS NOT NULL AND kind = 'ACTION' AND owner IS NULL)),
    ADD CONSTRAINT fk_meeting_minutes_item_owner_speaker FOREIGN KEY (tenant_id, meeting_id, owner_track, owner_label)
        REFERENCES meeting_speaker(tenant_id, meeting_id, track, label) ON DELETE CASCADE;

-- Stored owners that are exactly one voice's name, as the transcript named it to the model (SpeakerNames): the name
-- the owner gave it, online the unnamed microphone as the meeting's owner, otherwise the numbered label.
WITH named AS (
    SELECT s.tenant_id, s.meeting_id, s.track, s.label,
           lower(CASE
               WHEN s.name IS NOT NULL THEN s.name
               WHEN m.kind = 'ONLINE' AND s.track = 'MIC' THEN CASE WHEN m.language = 'en' THEN 'Owner' ELSE 'Chủ cuộc họp' END
               ELSE CASE WHEN m.language = 'en' THEN 'Speaker ' ELSE 'Người nói ' END || s.label
           END) AS shown
    FROM meeting_speaker s
    JOIN meeting m ON m.tenant_id = s.tenant_id AND m.id = s.meeting_id
), matched AS (
    SELECT i.tenant_id, i.id, min(n.track) AS track, min(n.label) AS label
    FROM meeting_minutes_item i
    JOIN named n ON n.tenant_id = i.tenant_id AND n.meeting_id = i.meeting_id AND n.shown = lower(btrim(i.owner))
    WHERE i.kind = 'ACTION' AND i.owner IS NOT NULL
    GROUP BY i.tenant_id, i.id
    HAVING count(*) = 1
)
UPDATE meeting_minutes_item i
SET owner_track = matched.track, owner_label = matched.label, owner = NULL
FROM matched
WHERE i.tenant_id = matched.tenant_id AND i.id = matched.id;
