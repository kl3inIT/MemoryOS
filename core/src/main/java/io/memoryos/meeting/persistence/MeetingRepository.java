package io.memoryos.meeting.persistence;

import io.memoryos.meeting.Meeting;
import java.sql.ResultSet;
import java.time.Duration;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Meetings, their speakers and utterances. Every statement is bounded by Tenant and owner. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class MeetingRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private final JdbcClient jdbc;

    public MeetingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The header row of an owned meeting. */
    public record Row(UUID id, String title, Meeting.Kind kind, @Nullable String language, List<String> participants,
                      List<String> terms, String notes, Meeting.Status status, @Nullable String provider, boolean diarized,
                      Instant createdAt, @Nullable Instant endedAt, long revision, Meeting.MinutesStatus minutesStatus,
                      @Nullable String minutesFailure, String minutesSummary, String minutesKind,
                      @Nullable Instant minutesGeneratedAt, Meeting.AudioStatus audioStatus,
                      @Nullable String audioFailure, @Nullable String audioFilename, long audioSizeBytes,
                      @Nullable String audioProvider) {}

    /** One meeting this replica leased to write minutes for. */
    public record MinutesClaim(UUID tenant, UUID id, UUID owner, int attempts) {}

    /** One uploaded recording this replica leased to transcribe, with everything the provider call needs. */
    public record AudioClaim(UUID tenant, UUID id, UUID owner, int attempts, UUID uploadId, String key,
                             String filename, String mediaType, long sizeBytes, @Nullable String provider) {}

    public void insert(UUID tenant, UUID id, UUID owner, Meeting.Draft draft) {
        jdbc.sql("""
                INSERT INTO meeting(tenant_id, id, owner_actor_id, title, kind, language, participants, terms)
                VALUES (:tenant, :id, :owner, :title, :kind, :language, CAST(:participants AS jsonb), CAST(:terms AS jsonb))
                """).param("tenant", tenant).param("id", id).param("owner", owner).param("title", draft.title())
                .param("kind", draft.kind().name()).param("language", draft.language())
                .param("participants", JSON.writeValueAsString(draft.participants()))
                .param("terms", JSON.writeValueAsString(draft.terms())).update();
    }

    public Optional<Row> find(UUID tenant, UUID owner, UUID id) {
        return jdbc.sql("""
                SELECT id, title, kind, language, participants, terms, notes, status, provider, diarized, created_at,
                       ended_at, revision, minutes_status, minutes_failure, minutes_summary, minutes_kind, minutes_generated_at,
                       audio_status, audio_failure, audio_filename, audio_size_bytes, audio_provider
                FROM meeting WHERE tenant_id = :tenant AND owner_actor_id = :owner AND id = :id
                """).param("tenant", tenant).param("owner", owner).param("id", id).query(MeetingRepository::row).optional();
    }

    /** Locks an owned meeting for a state change in the caller's transaction. */
    public Optional<Row> lock(UUID tenant, UUID owner, UUID id) {
        return jdbc.sql("""
                SELECT id, title, kind, language, participants, terms, notes, status, provider, diarized, created_at,
                       ended_at, revision, minutes_status, minutes_failure, minutes_summary, minutes_kind, minutes_generated_at,
                       audio_status, audio_failure, audio_filename, audio_size_bytes, audio_provider
                FROM meeting WHERE tenant_id = :tenant AND owner_actor_id = :owner AND id = :id FOR UPDATE
                """).param("tenant", tenant).param("owner", owner).param("id", id).query(MeetingRepository::row).optional();
    }

    /** Newest first, with the duration covered by utterances. */
    public List<Meeting.Summary> list(UUID tenant, UUID owner, int limit) {
        return jdbc.sql("""
                SELECT m.id, m.title, m.kind, m.status, jsonb_array_length(m.participants) AS participants,
                       COALESCE((SELECT max(u.end_ms) FROM meeting_utterance u
                                 WHERE u.tenant_id = m.tenant_id AND u.meeting_id = m.id), 0) AS duration_ms,
                       m.created_at, m.ended_at
                FROM meeting m WHERE m.tenant_id = :tenant AND m.owner_actor_id = :owner
                ORDER BY m.created_at DESC, m.id LIMIT :limit
                """).param("tenant", tenant).param("owner", owner).param("limit", limit)
                .query((r, ignored) -> new Meeting.Summary(r.getObject("id", UUID.class), r.getString("title"),
                        Meeting.Kind.valueOf(r.getString("kind")), Meeting.Status.valueOf(r.getString("status")),
                        r.getInt("participants"), r.getLong("duration_ms"), instant(r, "created_at"), instant(r, "ended_at")))
                .list();
    }

    public List<Meeting.Speaker> speakers(UUID tenant, UUID meeting) {
        return jdbc.sql("""
                SELECT track, label, name FROM meeting_speaker WHERE tenant_id = :tenant AND meeting_id = :meeting
                ORDER BY track, label
                """).param("tenant", tenant).param("meeting", meeting)
                .query((r, ignored) -> new Meeting.Speaker(Meeting.Track.valueOf(r.getString("track")), r.getString("label"),
                        r.getString("name"))).list();
    }

    public List<Meeting.Utterance> utterances(UUID tenant, UUID meeting) {
        return jdbc.sql("""
                SELECT id, track, speaker, start_ms, end_ms, text, confidence FROM meeting_utterance
                WHERE tenant_id = :tenant AND meeting_id = :meeting ORDER BY start_ms, end_ms, id
                """).param("tenant", tenant).param("meeting", meeting)
                .query((r, ignored) -> new Meeting.Utterance(r.getObject("id", UUID.class),
                        Meeting.Track.valueOf(r.getString("track")), r.getString("speaker"), r.getLong("start_ms"),
                        r.getLong("end_ms"), r.getString("text"), r.getDouble("confidence"))).list();
    }

    /** Stores one finalized utterance, creating its speaker row on first use. */
    public void insertUtterance(UUID tenant, UUID meeting, Meeting.Utterance utterance) {
        jdbc.sql("""
                INSERT INTO meeting_speaker(tenant_id, meeting_id, track, label) VALUES (:tenant, :meeting, :track, :label)
                ON CONFLICT DO NOTHING
                """).param("tenant", tenant).param("meeting", meeting).param("track", utterance.track().name())
                .param("label", utterance.speaker()).update();
        jdbc.sql("""
                INSERT INTO meeting_utterance(tenant_id, id, meeting_id, track, speaker, start_ms, end_ms, text, confidence)
                VALUES (:tenant, :id, :meeting, :track, :speaker, :start, :end, :text, :confidence)
                """).param("tenant", tenant).param("id", utterance.id()).param("meeting", meeting)
                .param("track", utterance.track().name()).param("speaker", utterance.speaker())
                .param("start", utterance.startMs()).param("end", utterance.endMs()).param("text", utterance.text())
                .param("confidence", (float) utterance.confidence()).update();
    }

    public void recordProvider(UUID tenant, UUID meeting, String provider, String model, boolean diarized) {
        jdbc.sql("""
                UPDATE meeting SET provider = :provider, model = :model, diarized = diarized OR :diarized
                WHERE tenant_id = :tenant AND id = :meeting
                """).param("tenant", tenant).param("meeting", meeting).param("provider", provider).param("model", model)
                .param("diarized", diarized).update();
    }

    /** Names a speaker; {@code null} restores the automatic label. Returns false for an unknown speaker. */
    public boolean nameSpeaker(UUID tenant, UUID meeting, Meeting.Track track, String label, @Nullable String name) {
        return jdbc.sql("""
                UPDATE meeting_speaker SET name = :name
                WHERE tenant_id = :tenant AND meeting_id = :meeting AND track = :track AND label = :label
                """).param("tenant", tenant).param("meeting", meeting).param("track", track.name()).param("label", label)
                .param("name", name).update() == 1;
    }

    public void updateNotes(UUID tenant, UUID meeting, String notes) {
        jdbc.sql("""
                UPDATE meeting SET notes = :notes, updated_at = CURRENT_TIMESTAMP, revision = revision + 1
                WHERE tenant_id = :tenant AND id = :meeting
                """).param("tenant", tenant).param("meeting", meeting).param("notes", notes).update();
    }

    public List<Meeting.MinutesItem> minutesItems(UUID tenant, UUID meeting) {
        return jdbc.sql("""
                SELECT id, kind, text, owner, due, quote, source_utterance_id, done FROM meeting_minutes_item
                WHERE tenant_id = :tenant AND meeting_id = :meeting ORDER BY kind, position, id
                """).param("tenant", tenant).param("meeting", meeting)
                .query((r, ignored) -> new Meeting.MinutesItem(r.getObject("id", UUID.class),
                        Meeting.ItemKind.valueOf(r.getString("kind")), r.getString("text"), r.getString("owner"),
                        r.getString("due"), r.getString("quote"), r.getObject("source_utterance_id", UUID.class),
                        r.getBoolean("done"))).list();
    }

    /** Queues the minutes of a meeting that just ended, or a rerun the owner asked for. */
    public void queueMinutes(UUID tenant, UUID meeting) {
        jdbc.sql("""
                UPDATE meeting SET minutes_status = 'PENDING', minutes_attempts = 0, minutes_lease_until = NULL,
                       minutes_failure = NULL
                WHERE tenant_id = :tenant AND id = :meeting
                """).param("tenant", tenant).param("meeting", meeting).update();
    }

    /**
     * Takes the oldest meeting waiting for minutes, or one whose lease lapsed, and leases it to this replica. The
     * attempt count rises on every claim, so a meeting that keeps failing stops being retried.
     */
    public Optional<MinutesClaim> claimMinutes(Duration lease, int maxAttempts) {
        return jdbc.sql("""
                UPDATE meeting m SET minutes_status = 'RUNNING', minutes_attempts = m.minutes_attempts + 1,
                       minutes_lease_until = now() + make_interval(secs => :lease)
                WHERE m.id = (
                    SELECT id FROM meeting
                    WHERE (minutes_status = 'PENDING' OR (minutes_status = 'RUNNING' AND minutes_lease_until < now()))
                      AND minutes_attempts < :max
                    ORDER BY ended_at, created_at LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING m.tenant_id, m.id, m.owner_actor_id, m.minutes_attempts
                """).param("lease", lease.toSeconds()).param("max", maxAttempts)
                .query((r, ignored) -> new MinutesClaim(r.getObject("tenant_id", UUID.class), r.getObject("id", UUID.class),
                        r.getObject("owner_actor_id", UUID.class), r.getInt("minutes_attempts"))).optional();
    }

    /** Stores one run's result. False means another replica took the meeting over after this lease lapsed. */
    public boolean writeMinutes(UUID tenant, UUID meeting, int attempt, String summary, String kind,
                                List<Meeting.MinutesItem> items) {
        boolean owned = jdbc.sql("""
                UPDATE meeting SET minutes_status = 'READY', minutes_lease_until = NULL, minutes_failure = NULL,
                       minutes_summary = :summary, minutes_kind = :kind, minutes_generated_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP, revision = revision + 1
                WHERE tenant_id = :tenant AND id = :meeting AND minutes_status = 'RUNNING' AND minutes_attempts = :attempt
                """).param("tenant", tenant).param("meeting", meeting).param("attempt", attempt)
                .param("summary", summary).param("kind", kind).update() == 1;
        if (!owned) return false;
        jdbc.sql("DELETE FROM meeting_minutes_item WHERE tenant_id = :tenant AND meeting_id = :meeting")
                .param("tenant", tenant).param("meeting", meeting).update();
        int position = 0;
        for (var item : items) {
            jdbc.sql("""
                    INSERT INTO meeting_minutes_item(tenant_id, id, meeting_id, kind, position, text, owner, due, quote,
                                                     source_utterance_id)
                    VALUES (:tenant, :id, :meeting, :kind, :position, :text, :owner, :due, :quote, :source)
                    """).param("tenant", tenant).param("id", item.id()).param("meeting", meeting)
                    .param("kind", item.kind().name()).param("position", position++).param("text", item.text())
                    .param("owner", item.owner()).param("due", item.due()).param("quote", item.quote())
                    .param("source", item.sourceUtteranceId()).update();
        }
        return true;
    }

    /** Records a failed run; the meeting waits for another attempt until the attempts run out. */
    public void failMinutes(UUID tenant, UUID meeting, int attempt, int maxAttempts, String failure) {
        jdbc.sql("""
                UPDATE meeting SET minutes_status = CASE WHEN :attempt >= :max THEN 'FAILED' ELSE 'PENDING' END,
                       minutes_lease_until = NULL, minutes_failure = :failure
                WHERE tenant_id = :tenant AND id = :meeting AND minutes_status = 'RUNNING' AND minutes_attempts = :attempt
                """).param("tenant", tenant).param("meeting", meeting).param("attempt", attempt).param("max", maxAttempts)
                .param("failure", failure).update();
    }

    /** Marks an owner's item done or not done. False when the item is not theirs. */
    public boolean markItem(UUID tenant, UUID meeting, UUID item, boolean done) {
        return jdbc.sql("""
                UPDATE meeting_minutes_item SET done = :done
                WHERE tenant_id = :tenant AND meeting_id = :meeting AND id = :item
                """).param("tenant", tenant).param("meeting", meeting).param("item", item).param("done", done).update() == 1;
    }

    public void end(UUID tenant, UUID meeting) {
        jdbc.sql("""
                UPDATE meeting SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP,
                       revision = revision + 1
                WHERE tenant_id = :tenant AND id = :meeting AND status = 'RECORDING'
                """).param("tenant", tenant).param("meeting", meeting).update();
    }

    public boolean delete(UUID tenant, UUID owner, UUID meeting) {
        return jdbc.sql("DELETE FROM meeting WHERE tenant_id = :tenant AND owner_actor_id = :owner AND id = :meeting")
                .param("tenant", tenant).param("owner", owner).param("meeting", meeting).update() == 1;
    }


    /** Records the reservation for a recording the browser is about to upload. */
    public void reserveAudio(UUID tenant, UUID meeting, UUID uploadId, String filename, String mediaType,
                             long sizeBytes, @Nullable String provider) {
        jdbc.sql("""
                UPDATE meeting SET audio_upload_id = :upload, audio_filename = :filename,
                       audio_media_type = :mediaType, audio_size_bytes = :size, audio_provider = :provider,
                       audio_status = 'WAITING', audio_attempts = 0, audio_lease_until = NULL, audio_failure = NULL,
                       status = 'TRANSCRIBING', updated_at = CURRENT_TIMESTAMP, revision = revision + 1
                WHERE tenant_id = :tenant AND id = :meeting
                """).param("tenant", tenant).param("meeting", meeting).param("upload", uploadId)
                .param("filename", filename).param("mediaType", mediaType).param("size", sizeBytes)
                .param("provider", provider).update();
    }

    /** Queues a reserved recording once its bytes are verified in object storage. */
    public boolean queueAudio(UUID tenant, UUID meeting, String key) {
        return jdbc.sql("""
                UPDATE meeting SET audio_status = 'PENDING', audio_attempts = 0, audio_lease_until = NULL,
                       audio_failure = NULL, audio_key = :key, status = 'TRANSCRIBING',
                       updated_at = CURRENT_TIMESTAMP, revision = revision + 1
                WHERE tenant_id = :tenant AND id = :meeting AND audio_status IN ('WAITING', 'FAILED')
                """).param("tenant", tenant).param("meeting", meeting).param("key", key).update() == 1;
    }

    /** Takes the oldest recording waiting to be transcribed, or one whose lease lapsed, and leases it here. */
    public Optional<AudioClaim> claimAudio(Duration lease, int maxAttempts) {
        return jdbc.sql("""
                UPDATE meeting m SET audio_status = 'RUNNING', audio_attempts = m.audio_attempts + 1,
                       audio_lease_until = now() + make_interval(secs => :lease)
                WHERE m.id = (
                    SELECT id FROM meeting
                    WHERE (audio_status = 'PENDING' OR (audio_status = 'RUNNING' AND audio_lease_until < now()))
                      AND audio_attempts < :max
                    ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING m.tenant_id, m.id, m.owner_actor_id, m.audio_attempts, m.audio_upload_id, m.audio_key,
                          m.audio_filename, m.audio_media_type, m.audio_size_bytes, m.audio_provider
                """).param("lease", lease.toSeconds()).param("max", maxAttempts)
                .query((r, ignored) -> new AudioClaim(r.getObject("tenant_id", UUID.class),
                        r.getObject("id", UUID.class), r.getObject("owner_actor_id", UUID.class),
                        r.getInt("audio_attempts"), r.getObject("audio_upload_id", UUID.class), r.getString("audio_key"),
                        r.getString("audio_filename"), r.getString("audio_media_type"), r.getLong("audio_size_bytes"),
                        r.getString("audio_provider"))).optional();
    }

    /**
     * Stores a transcribed recording as one meeting's utterances and ends the meeting. False means another replica
     * took it over after this lease lapsed, so this run's segments are dropped.
     */
    public boolean writeAudio(UUID tenant, UUID meeting, int attempt, String provider, String model, boolean diarized,
                              List<Meeting.Utterance> utterances) {
        boolean owned = jdbc.sql("""
                UPDATE meeting SET audio_status = 'DONE', audio_lease_until = NULL, audio_failure = NULL,
                       audio_key = NULL, status = 'ENDED', ended_at = CURRENT_TIMESTAMP, provider = :provider,
                       model = :model, diarized = :diarized, updated_at = CURRENT_TIMESTAMP, revision = revision + 1
                WHERE tenant_id = :tenant AND id = :meeting AND audio_status = 'RUNNING' AND audio_attempts = :attempt
                """).param("tenant", tenant).param("meeting", meeting).param("attempt", attempt)
                .param("provider", provider).param("model", model).param("diarized", diarized).update() == 1;
        if (!owned) return false;
        // insertUtterance creates the speaker row on first use, as a live track does.
        for (var utterance : utterances) insertUtterance(tenant, meeting, utterance);
        return true;
    }

    /** Records a failed run; the recording waits for another attempt until the attempts run out. */
    public void failAudio(UUID tenant, UUID meeting, int attempt, int maxAttempts, String failure) {
        jdbc.sql("""
                UPDATE meeting SET audio_status = CASE WHEN :attempt >= :max THEN 'FAILED' ELSE 'PENDING' END,
                       audio_lease_until = NULL, audio_failure = :failure,
                       status = CASE WHEN :attempt >= :max THEN 'ENDED' ELSE status END,
                       ended_at = CASE WHEN :attempt >= :max THEN CURRENT_TIMESTAMP ELSE ended_at END,
                       updated_at = CURRENT_TIMESTAMP, revision = revision + 1
                WHERE tenant_id = :tenant AND id = :meeting AND audio_status = 'RUNNING' AND audio_attempts = :attempt
                """).param("tenant", tenant).param("meeting", meeting).param("attempt", attempt).param("max", maxAttempts)
                .param("failure", failure).update();
    }

    /** The recording still held for a meeting, so its bytes can be retired when they are no longer needed. */
    public Optional<UUID> audioUpload(UUID tenant, UUID meeting) {
        return jdbc.sql("SELECT audio_upload_id FROM meeting WHERE tenant_id = :tenant AND id = :meeting")
                .param("tenant", tenant).param("meeting", meeting)
                .query((r, ignored) -> r.getObject("audio_upload_id", UUID.class)).optional();
    }

    /** Forgets the recording once its bytes have been retired; the transcript stays. */
    public void forgetAudio(UUID tenant, UUID meeting) {
        jdbc.sql("""
                UPDATE meeting SET audio_upload_id = NULL, audio_key = NULL WHERE tenant_id = :tenant AND id = :meeting
                """).param("tenant", tenant).param("meeting", meeting).update();
    }

    private static Row row(ResultSet r, int ignored) throws SQLException {
        return new Row(r.getObject("id", UUID.class), r.getString("title"), Meeting.Kind.valueOf(r.getString("kind")),
                r.getString("language"), strings(r.getString("participants")), strings(r.getString("terms")),
                r.getString("notes"), Meeting.Status.valueOf(r.getString("status")), r.getString("provider"),
                r.getBoolean("diarized"), instant(r, "created_at"), instant(r, "ended_at"), r.getLong("revision"),
                Meeting.MinutesStatus.valueOf(r.getString("minutes_status")), r.getString("minutes_failure"),
                r.getString("minutes_summary"), r.getString("minutes_kind"), instant(r, "minutes_generated_at"),
                Meeting.AudioStatus.valueOf(r.getString("audio_status")), r.getString("audio_failure"),
                r.getString("audio_filename"), r.getLong("audio_size_bytes"), r.getString("audio_provider"));
    }

    private static List<String> strings(String json) {
        return JSON.readValue(json, STRINGS);
    }

    private static @Nullable Instant instant(ResultSet r, String column) throws SQLException {
        Timestamp value = r.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
