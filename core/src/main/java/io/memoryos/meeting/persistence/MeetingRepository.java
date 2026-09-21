package io.memoryos.meeting.persistence;

import io.memoryos.meeting.Meeting;
import java.sql.ResultSet;
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
                      Instant createdAt, @Nullable Instant endedAt, long revision) {}

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
                       ended_at, revision
                FROM meeting WHERE tenant_id = :tenant AND owner_actor_id = :owner AND id = :id
                """).param("tenant", tenant).param("owner", owner).param("id", id).query(MeetingRepository::row).optional();
    }

    /** Locks an owned meeting for a state change in the caller's transaction. */
    public Optional<Row> lock(UUID tenant, UUID owner, UUID id) {
        return jdbc.sql("""
                SELECT id, title, kind, language, participants, terms, notes, status, provider, diarized, created_at,
                       ended_at, revision
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

    private static Row row(ResultSet r, int ignored) throws SQLException {
        return new Row(r.getObject("id", UUID.class), r.getString("title"), Meeting.Kind.valueOf(r.getString("kind")),
                r.getString("language"), strings(r.getString("participants")), strings(r.getString("terms")),
                r.getString("notes"), Meeting.Status.valueOf(r.getString("status")), r.getString("provider"),
                r.getBoolean("diarized"), instant(r, "created_at"), instant(r, "ended_at"), r.getLong("revision"));
    }

    private static List<String> strings(String json) {
        return JSON.readValue(json, STRINGS);
    }

    private static @Nullable Instant instant(ResultSet r, String column) throws SQLException {
        Timestamp value = r.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
