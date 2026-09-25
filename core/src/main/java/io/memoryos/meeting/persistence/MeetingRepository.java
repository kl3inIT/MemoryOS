package io.memoryos.meeting.persistence;

import io.memoryos.meeting.Meeting;
import io.memoryos.meeting.MeetingMinutesDocument;
import io.memoryos.shared.LeasedJob;
import java.sql.ResultSet;
import java.time.Duration;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;
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
    private static final TypeReference<List<Meeting.Span>> SPANS = new TypeReference<>() {};
    private final JdbcClient jdbc;

    /** The header row; {@code %s} is whether the caller owns it. */
    private static final String ROW = """
            SELECT m.id, m.title, m.kind, m.language, m.participants, m.terms, m.notes, m.status, m.provider,
                   m.diarized, m.created_at, m.ended_at, m.revision, m.minutes_status, m.minutes_failure,
                   m.minutes_summary, m.minutes_kind, m.minutes_generated_at, m.audio_status, m.audio_failure,
                   m.audio_filename, m.audio_size_bytes, m.audio_provider, %s AS owned, m.minutes_edited,
                   (m.correction_running_until > now()) IS TRUE AS correcting
            FROM meeting m
            """;
    private static final String UTTERANCE = "id, track, speaker, start_ms, end_ms, text, confidence, spans, edit_source";
    private static final String ITEM = "id, kind, text, owner, due, quote, source_utterance_id, done, edited";
    private static final String CORRECTION = """
            id, utterance_id, run_id, span_start, span_end, before, after, reason, confidence, context_fit, \
            meaning_safe, matched_glossary, status""";

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
                      @Nullable String audioProvider, boolean owned, boolean minutesEdited, boolean correcting) {}

    /** One meeting this replica leased to write minutes for. */
    public record MinutesClaim(UUID tenant, UUID id, UUID owner, int attempts) implements LeasedJob.Claim {}

    /** One uploaded recording this replica leased to transcribe, with everything the provider call needs. */
    public record AudioClaim(UUID tenant, UUID id, UUID owner, int attempts, UUID uploadId, String key,
                             String filename, String mediaType, long sizeBytes, @Nullable String provider)
            implements LeasedJob.Claim {}

    /** A meeting whose recording is transcribed or given up on, with the upload whose bytes it still holds. */
    public record HeldRecording(UUID tenant, UUID id, UUID uploadId) {}

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
        return jdbc.sql(ROW.formatted("TRUE") + """
                WHERE m.tenant_id = :tenant AND m.owner_actor_id = :owner AND m.id = :id
                """).param("tenant", tenant).param("owner", owner).param("id", id).query(MeetingRepository::row).optional();
    }

    /** The meeting as a reader sees it: their own, or one its owner shared with them or with a Group of theirs. */
    public Optional<Row> read(UUID tenant, UUID actor, UUID id) {
        return jdbc.sql(ROW.formatted("(m.owner_actor_id = :actor)")
                        + "WHERE m.tenant_id = :tenant AND m.id = :id AND " + MeetingAccessSql.READS)
                .param("tenant", tenant).param("actor", actor).param("id", id).query(MeetingRepository::row).optional();
    }

    /** Locks an owned meeting for a state change in the caller's transaction. */
    public Optional<Row> lock(UUID tenant, UUID owner, UUID id) {
        return jdbc.sql(ROW.formatted("TRUE") + """
                WHERE m.tenant_id = :tenant AND m.owner_actor_id = :owner AND m.id = :id FOR UPDATE
                """).param("tenant", tenant).param("owner", owner).param("id", id).query(MeetingRepository::row).optional();
    }

    /** The member's own meetings and the ones shared with them, newest first, with the duration covered by utterances. */
    public List<Meeting.Summary> list(UUID tenant, UUID actor, int limit) {
        return jdbc.sql("""
                SELECT m.id, m.title, m.kind, m.status, jsonb_array_length(m.participants) AS participants,
                       COALESCE((SELECT max(u.end_ms) FROM meeting_utterance u
                                 WHERE u.tenant_id = m.tenant_id AND u.meeting_id = m.id), 0) AS duration_ms,
                       m.created_at, m.ended_at, (m.owner_actor_id = :actor) AS owned
                FROM meeting m WHERE m.tenant_id = :tenant AND %s
                ORDER BY m.created_at DESC, m.id LIMIT :limit
                """.formatted(MeetingAccessSql.READS)).param("tenant", tenant).param("actor", actor).param("limit", limit)
                .query((r, ignored) -> new Meeting.Summary(r.getObject("id", UUID.class), r.getString("title"),
                        Meeting.Kind.valueOf(r.getString("kind")), Meeting.Status.valueOf(r.getString("status")),
                        r.getInt("participants"), r.getLong("duration_ms"), instant(r, "created_at"),
                        instant(r, "ended_at"), r.getBoolean("owned")))
                .list();
    }

    /** Everyone this meeting is shared with, by name, for the owner's "shared with" list. */
    public List<Meeting.Reader> readers(UUID tenant, UUID meeting) {
        var readers = new java.util.ArrayList<Meeting.Reader>();
        readers.addAll(jdbc.sql("""
                SELECT s.actor_id AS id, COALESCE(NULLIF(p.display_name, ''), p.email, '') AS name
                FROM meeting_user_share s
                LEFT JOIN actor_profiles p ON p.actor_id = s.actor_id
                WHERE s.tenant_id = :tenant AND s.meeting_id = :meeting ORDER BY name, s.actor_id
                """).param("tenant", tenant).param("meeting", meeting).query(reader(Meeting.ReaderKind.MEMBER)).list());
        readers.addAll(jdbc.sql("""
                SELECT s.group_id AS id, g.name FROM meeting_group_share s
                JOIN iam_groups g ON g.tenant_id = s.tenant_id AND g.id = s.group_id
                WHERE s.tenant_id = :tenant AND s.meeting_id = :meeting ORDER BY g.name, s.group_id
                """).param("tenant", tenant).param("meeting", meeting).query(reader(Meeting.ReaderKind.GROUP)).list());
        return List.copyOf(readers);
    }

    /** Replaces who a meeting is shared with. Members and Groups must already belong to the Tenant. */
    public void share(UUID tenant, UUID meeting, List<UUID> members, List<UUID> groups) {
        jdbc.sql("DELETE FROM meeting_user_share WHERE tenant_id = :tenant AND meeting_id = :meeting")
                .param("tenant", tenant).param("meeting", meeting).update();
        jdbc.sql("DELETE FROM meeting_group_share WHERE tenant_id = :tenant AND meeting_id = :meeting")
                .param("tenant", tenant).param("meeting", meeting).update();
        if (!members.isEmpty())
            jdbc.sql("""
                    INSERT INTO meeting_user_share(tenant_id, meeting_id, actor_id)
                    SELECT :tenant, :meeting, actor FROM unnest(CAST(:actors AS uuid[])) AS actor
                    """).param("tenant", tenant).param("meeting", meeting).param("actors", texts(members, UUID::toString))
                    .update();
        if (!groups.isEmpty())
            jdbc.sql("""
                    INSERT INTO meeting_group_share(tenant_id, meeting_id, group_id)
                    SELECT :tenant, :meeting, team FROM unnest(CAST(:groups AS uuid[])) AS team
                    """).param("tenant", tenant).param("meeting", meeting).param("groups", texts(groups, UUID::toString))
                    .update();
    }

    /** The Tenant members among the given actors, so a share never names somebody who is not one. */
    public List<UUID> members(UUID tenant, List<UUID> actors) {
        if (actors.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT actor_id FROM tenant_memberships
                WHERE tenant_id = :tenant AND actor_id IN (:actors) AND status = 'ACTIVE'
                """).param("tenant", tenant).param("actors", actors)
                .query((r, ignored) -> r.getObject("actor_id", UUID.class)).list();
    }

    /** The Tenant's Groups among the given ids. */
    public List<UUID> groups(UUID tenant, List<UUID> groups) {
        if (groups.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT id FROM iam_groups WHERE tenant_id = :tenant AND id IN (:groups) AND system_key IS NULL
                """)
                .param("tenant", tenant).param("groups", groups)
                .query((r, ignored) -> r.getObject("id", UUID.class)).list();
    }

    public List<Meeting.Speaker> speakers(UUID tenant, UUID meeting) {
        return jdbc.sql("""
                SELECT track, label, name FROM meeting_speaker
                WHERE tenant_id = :tenant AND meeting_id = :meeting
                ORDER BY track, label
                """).param("tenant", tenant).param("meeting", meeting).query(MeetingRepository::speaker).list();
    }

    /** The voices whose offered name the owner has not answered yet: unnamed, and not dismissed. */
    public List<Meeting.Speaker> speakersAskingForAName(UUID tenant, UUID meeting) {
        return jdbc.sql("""
                SELECT track, label, name FROM meeting_speaker
                WHERE tenant_id = :tenant AND meeting_id = :meeting AND name IS NULL AND NOT suggestion_dismissed
                """).param("tenant", tenant).param("meeting", meeting).query(MeetingRepository::speaker).list();
    }

    /** Stops offering a name for this voice. Returns false for an unknown speaker. */
    public boolean dismissSuggestion(UUID tenant, UUID meeting, Meeting.Track track, String label) {
        return jdbc.sql("""
                UPDATE meeting_speaker SET suggestion_dismissed = true
                WHERE tenant_id = :tenant AND meeting_id = :meeting AND track = :track AND label = :label
                """).param("tenant", tenant).param("meeting", meeting).param("track", track.name())
                .param("label", label).update() == 1;
    }

    public List<Meeting.Utterance> utterances(UUID tenant, UUID meeting) {
        return jdbc.sql("""
                SELECT %s FROM meeting_utterance
                WHERE tenant_id = :tenant AND meeting_id = :meeting ORDER BY start_ms, end_ms, id
                """.formatted(UTTERANCE)).param("tenant", tenant).param("meeting", meeting)
                .query(MeetingRepository::utterance).list();
    }

    /** What one voice said, in order: enough to read the name it gave itself without reading the whole meeting. */
    public List<Meeting.Utterance> utterancesOf(UUID tenant, UUID meeting, Meeting.Track track, String speaker) {
        return jdbc.sql("""
                SELECT %s FROM meeting_utterance
                WHERE tenant_id = :tenant AND meeting_id = :meeting AND track = :track AND speaker = :speaker
                ORDER BY start_ms, end_ms, id
                """.formatted(UTTERANCE)).param("tenant", tenant).param("meeting", meeting).param("track", track.name())
                .param("speaker", speaker).query(MeetingRepository::utterance).list();
    }

    /** Stores one finalized utterance, creating its speaker row on first use. */
    public void insertUtterance(UUID tenant, UUID meeting, Meeting.Utterance utterance) {
        insertUtterances(tenant, meeting, List.of(utterance));
    }

    /**
     * Stores finalized utterances in two statements however many there are, creating each speaker row on first use.
     * An uploaded recording arrives as thousands of lines at once.
     */
    public void insertUtterances(UUID tenant, UUID meeting, List<Meeting.Utterance> utterances) {
        if (utterances.isEmpty()) return;
        String[] tracks = texts(utterances, utterance -> utterance.track().name());
        String[] speakers = texts(utterances, Meeting.Utterance::speaker);
        jdbc.sql("""
                INSERT INTO meeting_speaker(tenant_id, meeting_id, track, label)
                SELECT DISTINCT :tenant, :meeting, voice.track, voice.label
                FROM unnest(CAST(:tracks AS text[]), CAST(:labels AS text[])) AS voice(track, label)
                ON CONFLICT DO NOTHING
                """).param("tenant", tenant).param("meeting", meeting).param("tracks", tracks).param("labels", speakers)
                .update();
        jdbc.sql("""
                INSERT INTO meeting_utterance(tenant_id, id, meeting_id, track, speaker, start_ms, end_ms, text,
                                              confidence, spans)
                SELECT :tenant, line.id, :meeting, line.track, line.speaker, line.start_ms, line.end_ms, line.text,
                       line.confidence, line.spans
                FROM unnest(CAST(:ids AS uuid[]), CAST(:tracks AS text[]), CAST(:speakers AS text[]),
                            CAST(:starts AS bigint[]), CAST(:ends AS bigint[]), CAST(:texts AS text[]),
                            CAST(:confidences AS real[]), CAST(:spans AS jsonb[]))
                         AS line(id, track, speaker, start_ms, end_ms, text, confidence, spans)
                """).param("tenant", tenant).param("meeting", meeting)
                .param("ids", texts(utterances, utterance -> utterance.id().toString()))
                .param("tracks", tracks).param("speakers", speakers)
                .param("starts", texts(utterances, utterance -> Long.toString(utterance.startMs())))
                .param("ends", texts(utterances, utterance -> Long.toString(utterance.endMs())))
                .param("texts", texts(utterances, Meeting.Utterance::text))
                .param("confidences", texts(utterances, utterance -> Float.toString((float) utterance.confidence())))
                .param("spans", texts(utterances, utterance -> JSON.writeValueAsString(utterance.spans()))).update();
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

    /** Stores the owner's notes and returns the meeting's new revision. */
    public long updateNotes(UUID tenant, UUID meeting, String notes) {
        return jdbc.sql("""
                UPDATE meeting SET notes = :notes, updated_at = CURRENT_TIMESTAMP, revision = revision + 1
                WHERE tenant_id = :tenant AND id = :meeting RETURNING revision
                """).param("tenant", tenant).param("meeting", meeting).param("notes", notes)
                .query(Long.class).single();
    }

    /** Stores the name and the people of a meeting and returns its new revision. */
    public long updateDetails(UUID tenant, UUID meeting, String title, List<String> participants) {
        return jdbc.sql("""
                UPDATE meeting SET title = :title, participants = CAST(:participants AS jsonb),
                                   updated_at = CURRENT_TIMESTAMP, revision = revision + 1
                WHERE tenant_id = :tenant AND id = :meeting RETURNING revision
                """).param("tenant", tenant).param("meeting", meeting).param("title", title)
                .param("participants", JSON.writeValueAsString(participants)).query(Long.class).single();
    }

    public Optional<MeetingMinutesDocument.Heading> heading(UUID tenant, UUID meeting) {
        return jdbc.sql("SELECT minutes_heading FROM meeting WHERE tenant_id = :tenant AND id = :meeting")
                .param("tenant", tenant).param("meeting", meeting)
                .query((r, ignored) -> r.getString("minutes_heading")).optional()
                .map(json -> JSON.readValue(json, MeetingMinutesDocument.Heading.class));
    }

    public void saveHeading(UUID tenant, UUID meeting, MeetingMinutesDocument.Heading heading) {
        jdbc.sql("""
                UPDATE meeting SET minutes_heading = CAST(:heading AS jsonb), updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND id = :meeting
                """).param("tenant", tenant).param("meeting", meeting)
                .param("heading", JSON.writeValueAsString(heading)).update();
    }

    public List<Meeting.MinutesItem> minutesItems(UUID tenant, UUID meeting) {
        return jdbc.sql("""
                SELECT %s FROM meeting_minutes_item
                WHERE tenant_id = :tenant AND meeting_id = :meeting ORDER BY kind, position, id
                """.formatted(ITEM)).param("tenant", tenant).param("meeting", meeting)
                .query(MeetingRepository::item).list();
    }

    /** Queues the minutes of a meeting that just ended, or a rerun the owner asked for. */
    public void queueMinutes(UUID tenant, UUID meeting) {
        jdbc.sql("""
                UPDATE meeting SET minutes_status = 'PENDING', minutes_attempts = 0, minutes_lease_until = NULL,
                       minutes_edited = FALSE,
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
        if (items.isEmpty()) return true;
        // The position is the item's place in the list the model wrote, counted across kinds as it always was.
        jdbc.sql("""
                INSERT INTO meeting_minutes_item(tenant_id, id, meeting_id, kind, position, text, owner, due, quote,
                                                 source_utterance_id)
                SELECT :tenant, item.id, :meeting, item.kind, item.position - 1, item.text, item.owner, item.due,
                       item.quote, item.source
                FROM unnest(CAST(:ids AS uuid[]), CAST(:kinds AS text[]), CAST(:texts AS text[]),
                            CAST(:owners AS text[]), CAST(:dues AS text[]), CAST(:quotes AS text[]),
                            CAST(:sources AS uuid[]))
                         WITH ORDINALITY AS item(id, kind, text, owner, due, quote, source, position)
                """).param("tenant", tenant).param("meeting", meeting)
                .param("ids", texts(items, item -> item.id().toString()))
                .param("kinds", texts(items, item -> item.kind().name()))
                .param("texts", texts(items, Meeting.MinutesItem::text))
                .param("owners", texts(items, Meeting.MinutesItem::owner))
                .param("dues", texts(items, Meeting.MinutesItem::due))
                .param("quotes", texts(items, Meeting.MinutesItem::quote))
                .param("sources", texts(items, item -> item.sourceUtteranceId() == null ? null
                        : item.sourceUtteranceId().toString()))
                .update();
        return true;
    }

    /**
     * Fails the minutes whose last permitted attempt ran out of lease without finishing, as a failed last attempt
     * would; a claim never takes them again.
     */
    public int failAbandonedMinutes(int maxAttempts) {
        return jdbc.sql("""
                UPDATE meeting SET minutes_status = 'FAILED', minutes_lease_until = NULL,
                       minutes_failure = 'MEETING_MINUTES_FAILED'
                WHERE minutes_status = 'RUNNING' AND minutes_lease_until < now() AND minutes_attempts >= :max
                """).param("max", maxAttempts).update();
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

    /** One item of the minutes, locked for the change about to be made to it. */
    public Optional<Meeting.MinutesItem> lockItem(UUID tenant, UUID meeting, UUID item) {
        return jdbc.sql("""
                SELECT %s FROM meeting_minutes_item
                WHERE tenant_id = :tenant AND meeting_id = :meeting AND id = :item FOR UPDATE
                """.formatted(ITEM)).param("tenant", tenant).param("meeting", meeting).param("item", item)
                .query(MeetingRepository::item).optional();
    }

    /** Writes what an item now says. The event beside it is the only history of what it said before. */
    /** An item the owner wrote in, after the others of its kind. */
    public void addItem(UUID tenant, UUID meeting, Meeting.MinutesItem item) {
        jdbc.sql("""
                INSERT INTO meeting_minutes_item(tenant_id, id, meeting_id, kind, position, text, owner, due, edited)
                VALUES (:tenant, :id, :meeting, :kind,
                        (SELECT COALESCE(max(position) + 1, 0) FROM meeting_minutes_item
                         WHERE tenant_id = :tenant AND meeting_id = :meeting AND kind = :kind),
                        :text, :owner, :due, TRUE)
                """).param("tenant", tenant).param("id", item.id()).param("meeting", meeting)
                .param("kind", item.kind().name()).param("text", item.text()).param("owner", item.owner())
                .param("due", item.due()).update();
        markMinutesEdited(tenant, meeting);
    }

    public boolean removeItem(UUID tenant, UUID meeting, UUID item) {
        boolean removed = jdbc.sql("""
                DELETE FROM meeting_minutes_item WHERE tenant_id = :tenant AND meeting_id = :meeting AND id = :item
                """).param("tenant", tenant).param("meeting", meeting).param("item", item).update() == 1;
        if (removed) markMinutesEdited(tenant, meeting);
        return removed;
    }

    /** The heading of the owner's most recent biên bản, which the next one starts from. */
    public Optional<MeetingMinutesDocument.Heading> lastHeading(UUID tenant, UUID owner) {
        return jdbc.sql("""
                SELECT minutes_heading FROM meeting
                WHERE tenant_id = :tenant AND owner_actor_id = :owner AND minutes_heading IS NOT NULL
                ORDER BY updated_at DESC, id LIMIT 1
                """).param("tenant", tenant).param("owner", owner)
                .query((r, ignored) -> r.getString("minutes_heading")).optional()
                .map(json -> JSON.readValue(json, MeetingMinutesDocument.Heading.class));
    }

    public void rewriteItem(UUID tenant, UUID meeting, Meeting.MinutesItem item) {
        jdbc.sql("""
                UPDATE meeting_minutes_item SET text = :text, owner = :owner, due = :due, edited = TRUE
                WHERE tenant_id = :tenant AND meeting_id = :meeting AND id = :item
                """).param("tenant", tenant).param("meeting", meeting).param("item", item.id())
                .param("text", item.text()).param("owner", item.owner()).param("due", item.due()).update();
        markMinutesEdited(tenant, meeting);
    }

    public void rewriteSummary(UUID tenant, UUID meeting, String summary) {
        jdbc.sql("""
                UPDATE meeting SET minutes_summary = :summary WHERE tenant_id = :tenant AND id = :meeting
                """).param("tenant", tenant).param("meeting", meeting).param("summary", summary).update();
        markMinutesEdited(tenant, meeting);
    }

    private void markMinutesEdited(UUID tenant, UUID meeting) {
        jdbc.sql("UPDATE meeting SET minutes_edited = TRUE WHERE tenant_id = :tenant AND id = :meeting")
                .param("tenant", tenant).param("meeting", meeting).update();
    }

    /** Records one change to the minutes, so the words the model wrote stay readable after they are replaced. */
    public void recordMinutesEdit(UUID tenant, UUID meeting, @Nullable UUID item, Meeting.MinutesField field,
            UUID actor, String before, String after) {
        jdbc.sql("""
                INSERT INTO meeting_minutes_event(tenant_id, meeting_id, item_id, field, actor_id, before, after)
                VALUES (:tenant, :meeting, :item, :field, :actor, :before, :after)
                """).param("tenant", tenant).param("meeting", meeting).param("item", item)
                .param("field", field.name()).param("actor", actor).param("before", before).param("after", after)
                .update();
    }

    /**
     * Marks a decision or a piece of work done or not done and returns it as it now reads; empty when the meeting has
     * no such item. A topic is a place in the timeline, not something to finish, so it is not found here.
     */
    public Optional<Meeting.MinutesItem> markItem(UUID tenant, UUID meeting, UUID item, boolean done) {
        return jdbc.sql("""
                UPDATE meeting_minutes_item SET done = :done
                WHERE tenant_id = :tenant AND meeting_id = :meeting AND id = :item AND kind <> 'TOPIC'
                RETURNING %s
                """.formatted(ITEM)).param("tenant", tenant).param("meeting", meeting).param("item", item).param("done", done)
                .query(MeetingRepository::item).optional();
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
        // The speaker rows are created on first use, as a live track creates them.
        insertUtterances(tenant, meeting, utterances);
        return true;
    }

    /**
     * Fails the recordings whose last permitted attempt ran out of lease without finishing and ends their meetings, as
     * a failed last attempt would. Their bytes are retired by {@link #heldRecordings}' sweep, as every finished one is.
     */
    public int failAbandonedAudio(int maxAttempts) {
        return jdbc.sql("""
                UPDATE meeting SET audio_status = 'FAILED', audio_lease_until = NULL,
                       audio_failure = 'MEETING_RECORDING_FAILED', status = 'ENDED', ended_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP, revision = revision + 1
                WHERE audio_status = 'RUNNING' AND audio_lease_until < now() AND audio_attempts >= :max
                """).param("max", maxAttempts).update();
    }

    /**
     * Meetings whose recording is done with — transcribed or given up on — but still hold its upload. The meeting row
     * is the durable marker: its upload is forgotten only after the bytes are retired, so a process that stops between
     * the two leaves the row here for the next sweep instead of an adopted upload nobody retires.
     */
    public List<HeldRecording> heldRecordings(int limit) {
        return jdbc.sql("""
                SELECT tenant_id, id, audio_upload_id FROM meeting
                WHERE audio_upload_id IS NOT NULL AND audio_status IN ('DONE', 'FAILED')
                ORDER BY updated_at, id LIMIT :limit
                """).param("limit", limit)
                .query((r, ignored) -> new HeldRecording(r.getObject("tenant_id", UUID.class),
                        r.getObject("id", UUID.class), r.getObject("audio_upload_id", UUID.class))).list();
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

    /**
     * Forgets the recording once its bytes have been retired; the transcript stays. Only that upload is forgotten, so a
     * sweep that read the row before the owner reserved another recording cannot drop the new one.
     */
    public void forgetAudio(UUID tenant, UUID meeting, UUID upload) {
        jdbc.sql("""
                UPDATE meeting SET audio_upload_id = NULL, audio_key = NULL
                WHERE tenant_id = :tenant AND id = :meeting AND audio_upload_id = :upload
                """).param("tenant", tenant).param("meeting", meeting).param("upload", upload).update();
    }

    /** The lines this reader starred, so a meeting five people read collects five sets of marks. */
    public List<UUID> starred(UUID tenant, UUID meeting, UUID actor) {
        return jdbc.sql("""
                SELECT utterance_id FROM meeting_utterance_star
                WHERE tenant_id = :tenant AND meeting_id = :meeting AND actor_id = :actor
                """).param("tenant", tenant).param("meeting", meeting).param("actor", actor)
                .query(UUID.class).list();
    }

    /** Stars a line for this reader; starring twice is starring once. */
    public void star(UUID tenant, UUID meeting, UUID utterance, UUID actor) {
        jdbc.sql("""
                INSERT INTO meeting_utterance_star(tenant_id, meeting_id, utterance_id, actor_id)
                VALUES (:tenant, :meeting, :utterance, :actor) ON CONFLICT DO NOTHING
                """).param("tenant", tenant).param("meeting", meeting).param("utterance", utterance)
                .param("actor", actor).update();
    }

    public void unstar(UUID tenant, UUID utterance, UUID actor) {
        jdbc.sql("""
                DELETE FROM meeting_utterance_star
                WHERE tenant_id = :tenant AND utterance_id = :utterance AND actor_id = :actor
                """).param("tenant", tenant).param("utterance", utterance).param("actor", actor).update();
    }

    /** Whether anybody said anything in this meeting, without reading the transcript. */
    public boolean hasUtterances(UUID tenant, UUID meeting) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM meeting_utterance WHERE tenant_id = :tenant AND meeting_id = :meeting)
                """).param("tenant", tenant).param("meeting", meeting).query(Boolean.class).single());
    }

    /** Whether this line belongs to this meeting, which is what makes starring it meaningful. */
    public boolean hasUtterance(UUID tenant, UUID meeting, UUID utterance) {
        return jdbc.sql("""
                SELECT count(*) FROM meeting_utterance
                WHERE tenant_id = :tenant AND meeting_id = :meeting AND id = :utterance
                """).param("tenant", tenant).param("meeting", meeting).param("utterance", utterance)
                .query(Integer.class).single() > 0;
    }

    public List<Meeting.Bookmark> bookmarks(UUID tenant, UUID meeting, UUID actor) {
        return jdbc.sql("""
                SELECT id, at_ms, label FROM meeting_bookmark
                WHERE tenant_id = :tenant AND meeting_id = :meeting AND actor_id = :actor ORDER BY at_ms, id
                """).param("tenant", tenant).param("meeting", meeting).param("actor", actor)
                .query((r, ignored) -> new Meeting.Bookmark(r.getObject("id", UUID.class), r.getLong("at_ms"),
                        r.getString("label"))).list();
    }

    public void addBookmark(UUID tenant, UUID meeting, UUID actor, Meeting.Bookmark bookmark) {
        jdbc.sql("""
                INSERT INTO meeting_bookmark(tenant_id, id, meeting_id, actor_id, at_ms, label)
                VALUES (:tenant, :id, :meeting, :actor, :at, :label)
                """).param("tenant", tenant).param("id", bookmark.id()).param("meeting", meeting)
                .param("actor", actor).param("at", bookmark.atMs()).param("label", bookmark.label()).update();
    }

    public boolean deleteBookmark(UUID tenant, UUID meeting, UUID actor, UUID id) {
        return jdbc.sql("""
                DELETE FROM meeting_bookmark
                WHERE tenant_id = :tenant AND meeting_id = :meeting AND actor_id = :actor AND id = :id
                """).param("tenant", tenant).param("meeting", meeting).param("actor", actor).param("id", id)
                .update() == 1;
    }

    /**
     * Claims the right to run one correction pass over this meeting. The owner is watching the request, so this is a
     * window rather than a lease: a pass that outlives it is abandoned and the next press starts a new one.
     */
    public boolean beginCorrection(UUID tenant, UUID owner, UUID meeting, Duration window) {
        return jdbc.sql("""
                UPDATE meeting SET correction_running_until = now() + make_interval(secs => :window)
                WHERE tenant_id = :tenant AND id = :meeting AND owner_actor_id = :owner
                  AND (correction_running_until IS NULL OR correction_running_until < CURRENT_TIMESTAMP)
                """).param("tenant", tenant).param("meeting", meeting).param("owner", owner)
                .param("window", window.toSeconds()).update() == 1;
    }

    public void endCorrection(UUID tenant, UUID meeting) {
        jdbc.sql("UPDATE meeting SET correction_running_until = NULL WHERE tenant_id = :tenant AND id = :meeting")
                .param("tenant", tenant).param("meeting", meeting).update();
    }

    /** Stores one pass's proposals. They are offers: no utterance changes until somebody decides. */
    public void insertCorrections(UUID tenant, UUID meeting, UUID run, List<Meeting.Correction> corrections) {
        if (corrections.isEmpty()) return;
        jdbc.sql("""
                INSERT INTO meeting_correction(tenant_id, id, meeting_id, utterance_id, run_id, span_start, span_end,
                                               before, after, reason, confidence, context_fit, meaning_safe,
                                               matched_glossary)
                SELECT :tenant, offer.id, :meeting, offer.utterance, :run, offer.span_start, offer.span_end,
                       offer.before, offer.after, offer.reason, offer.confidence, offer.context_fit,
                       offer.meaning_safe, offer.glossary
                FROM unnest(CAST(:ids AS uuid[]), CAST(:utterances AS uuid[]), CAST(:starts AS int[]),
                            CAST(:ends AS int[]), CAST(:befores AS text[]), CAST(:afters AS text[]),
                            CAST(:reasons AS text[]), CAST(:confidences AS real[]), CAST(:fits AS real[]),
                            CAST(:safes AS real[]), CAST(:glossaries AS boolean[]))
                         AS offer(id, utterance, span_start, span_end, before, after, reason, confidence,
                                  context_fit, meaning_safe, glossary)
                """).param("tenant", tenant).param("meeting", meeting).param("run", run)
                .param("ids", texts(corrections, correction -> correction.id().toString()))
                .param("utterances", texts(corrections, correction -> correction.utteranceId().toString()))
                .param("starts", texts(corrections, correction -> Integer.toString(correction.start())))
                .param("ends", texts(corrections, correction -> Integer.toString(correction.end())))
                .param("befores", texts(corrections, Meeting.Correction::before))
                .param("afters", texts(corrections, Meeting.Correction::after))
                .param("reasons", texts(corrections, Meeting.Correction::reason))
                .param("confidences", texts(corrections, correction -> Float.toString((float) correction.confidence())))
                .param("fits", texts(corrections, correction -> Float.toString((float) correction.contextFit())))
                .param("safes", texts(corrections, correction -> Float.toString((float) correction.meaningSafe())))
                .param("glossaries", texts(corrections, correction -> Boolean.toString(correction.matchedGlossary())))
                .update();
    }

    public List<Meeting.Correction> corrections(UUID tenant, UUID meeting) {
        return jdbc.sql("""
                SELECT %s
                FROM meeting_correction WHERE tenant_id = :tenant AND meeting_id = :meeting
                ORDER BY created_at, id
                """.formatted(CORRECTION)).param("tenant", tenant).param("meeting", meeting).query(MeetingRepository::correction).list();
    }

    /** One proposal, locked, so two decisions on the same stretch cannot both land. */
    public Optional<Meeting.Correction> lockCorrection(UUID tenant, UUID meeting, UUID id) {
        return jdbc.sql("""
                SELECT %s
                FROM meeting_correction WHERE tenant_id = :tenant AND meeting_id = :meeting AND id = :id FOR UPDATE
                """.formatted(CORRECTION)).param("tenant", tenant).param("meeting", meeting).param("id", id)
                .query(MeetingRepository::correction).optional();
    }

    /** What one pass actually put into the transcript, newest first, so a whole pass can be taken back at once. */
    public List<Meeting.Correction> acceptedOfRun(UUID tenant, UUID meeting, UUID run) {
        return jdbc.sql("""
                SELECT %s
                FROM meeting_correction
                WHERE tenant_id = :tenant AND meeting_id = :meeting AND run_id = :run AND status = 'ACCEPTED'
                ORDER BY decided_at DESC, id DESC FOR UPDATE
                """.formatted(CORRECTION)).param("tenant", tenant).param("meeting", meeting).param("run", run)
                .query(MeetingRepository::correction).list();
    }

    public List<Meeting.Correction> pendingOfRun(UUID tenant, UUID meeting, UUID run) {
        return jdbc.sql("""
                SELECT %s
                FROM meeting_correction
                WHERE tenant_id = :tenant AND meeting_id = :meeting AND run_id = :run AND status = 'PENDING'
                ORDER BY created_at, id FOR UPDATE
                """.formatted(CORRECTION)).param("tenant", tenant).param("meeting", meeting).param("run", run)
                .query(MeetingRepository::correction).list();
    }

    /**
     * Records the decision and the words that actually went in, which are the owner's when they rewrote them, so
     * taking it back puts back exactly those words. Answers the proposal as it now stands.
     */
    public Meeting.Correction accepted(UUID tenant, UUID id, UUID actor, int start, int end, String before,
            String after) {
        return jdbc.sql("""
                UPDATE meeting_correction
                SET status = 'ACCEPTED', span_start = :start, span_end = :end, before = :before, after = :after,
                    decided_at = CURRENT_TIMESTAMP, decided_by = :actor
                WHERE tenant_id = :tenant AND id = :id
                RETURNING %s
                """.formatted(CORRECTION)).param("tenant", tenant).param("id", id).param("start", start).param("end", end)
                .param("before", before).param("after", after).param("actor", actor)
                .query(MeetingRepository::correction).single();
    }

    /** Records a decision that puts nothing in, and answers the proposal as it now stands. */
    public Meeting.Correction decide(UUID tenant, UUID id, Meeting.CorrectionStatus status, UUID actor) {
        return jdbc.sql("""
                UPDATE meeting_correction
                SET status = :status, decided_at = CURRENT_TIMESTAMP, decided_by = :actor
                WHERE tenant_id = :tenant AND id = :id
                RETURNING %s
                """.formatted(CORRECTION)).param("tenant", tenant).param("id", id).param("status", status.name())
                .param("actor", actor).query(MeetingRepository::correction).single();
    }

    /** One utterance, locked for the change about to be made to it. */
    public Optional<Meeting.Utterance> lockUtterance(UUID tenant, UUID meeting, UUID id) {
        return jdbc.sql("""
                SELECT %s FROM meeting_utterance
                WHERE tenant_id = :tenant AND meeting_id = :meeting AND id = :id FOR UPDATE
                """.formatted(UTTERANCE)).param("tenant", tenant).param("meeting", meeting).param("id", id)
                .query(MeetingRepository::utterance).optional();
    }

    /**
     * Writes what a line now says, together with the event that records the change, and answers the line as a reader
     * now sees it. The event is the only history: the words the provider first wrote are the {@code before} of the
     * oldest one.
     */
    public Meeting.Utterance rewrite(UUID tenant, UUID meeting, UUID utterance, String before, String after,
            List<Meeting.Span> spans, Meeting.@Nullable EditSource source, @Nullable UUID run, UUID actor,
            String eventSource) {
        var rewritten = jdbc.sql("""
                UPDATE meeting_utterance SET text = :text, spans = CAST(:spans AS jsonb), edit_source = :source
                WHERE tenant_id = :tenant AND id = :utterance
                RETURNING %s
                """.formatted(UTTERANCE)).param("tenant", tenant).param("utterance", utterance).param("text", after)
                .param("spans", JSON.writeValueAsString(spans))
                .param("source", source == null ? null : source.name()).query(MeetingRepository::utterance).single();
        jdbc.sql("""
                INSERT INTO meeting_utterance_event(tenant_id, meeting_id, utterance_id, run_id, actor_id, source,
                                                    before, after)
                VALUES (:tenant, :meeting, :utterance, :run, :actor, :eventSource, :before, :after)
                """).param("tenant", tenant).param("meeting", meeting).param("utterance", utterance)
                .param("run", run).param("actor", actor).param("eventSource", eventSource)
                .param("before", before).param("after", after).update();
        return rewritten;
    }

    /**
     * Who owns the words a line is about to be left with: the source of the newest change that produced exactly this
     * text, or nothing at all when it is back to what the provider first wrote.
     */
    public Meeting.@Nullable EditSource standingEdit(UUID tenant, UUID utterance, String text) {
        return jdbc.sql("""
                SELECT source FROM meeting_utterance_event
                WHERE tenant_id = :tenant AND utterance_id = :utterance AND after = :text AND source <> 'REVERT'
                ORDER BY id DESC LIMIT 1
                """).param("tenant", tenant).param("utterance", utterance).param("text", text)
                .query(String.class).optional().map(Meeting.EditSource::valueOf).orElse(null);
    }

    private static Meeting.Utterance utterance(ResultSet r, int ignored) throws SQLException {
        return new Meeting.Utterance(r.getObject("id", UUID.class), Meeting.Track.valueOf(r.getString("track")),
                r.getString("speaker"), r.getLong("start_ms"), r.getLong("end_ms"), r.getString("text"),
                r.getDouble("confidence"), JSON.readValue(r.getString("spans"), SPANS), editSource(r));
    }

    private static Meeting.Speaker speaker(ResultSet r, int ignored) throws SQLException {
        return new Meeting.Speaker(Meeting.Track.valueOf(r.getString("track")), r.getString("label"), r.getString("name"));
    }

    private static RowMapper<Meeting.Reader> reader(Meeting.ReaderKind kind) {
        return (r, ignored) -> new Meeting.Reader(kind, r.getObject("id", UUID.class), r.getString("name"));
    }

    private static Meeting.MinutesItem item(ResultSet r, int ignored) throws SQLException {
        return new Meeting.MinutesItem(r.getObject("id", UUID.class), Meeting.ItemKind.valueOf(r.getString("kind")),
                r.getString("text"), r.getString("owner"), r.getString("due"), r.getString("quote"),
                r.getObject("source_utterance_id", UUID.class), r.getBoolean("done"), r.getBoolean("edited"));
    }

    private static Meeting.Correction correction(ResultSet r, int ignored) throws SQLException {
        return new Meeting.Correction(r.getObject("id", UUID.class), r.getObject("utterance_id", UUID.class),
                r.getObject("run_id", UUID.class), r.getInt("span_start"), r.getInt("span_end"),
                r.getString("before"), r.getString("after"), r.getString("reason"), r.getDouble("confidence"),
                r.getDouble("context_fit"), r.getDouble("meaning_safe"), r.getBoolean("matched_glossary"),
                Meeting.CorrectionStatus.valueOf(r.getString("status")));
    }

    private static Row row(ResultSet r, int ignored) throws SQLException {
        return new Row(r.getObject("id", UUID.class), r.getString("title"), Meeting.Kind.valueOf(r.getString("kind")),
                r.getString("language"), strings(r.getString("participants")), strings(r.getString("terms")),
                r.getString("notes"), Meeting.Status.valueOf(r.getString("status")), r.getString("provider"),
                r.getBoolean("diarized"), instant(r, "created_at"), instant(r, "ended_at"), r.getLong("revision"),
                Meeting.MinutesStatus.valueOf(r.getString("minutes_status")), r.getString("minutes_failure"),
                r.getString("minutes_summary"), r.getString("minutes_kind"), instant(r, "minutes_generated_at"),
                Meeting.AudioStatus.valueOf(r.getString("audio_status")), r.getString("audio_failure"),
                r.getString("audio_filename"), r.getLong("audio_size_bytes"), r.getString("audio_provider"),
                r.getBoolean("owned"), r.getBoolean("minutes_edited"), r.getBoolean("correcting"));
    }

    private static Meeting.@Nullable EditSource editSource(ResultSet r) throws SQLException {
        String value = r.getString("edit_source");
        return value == null ? null : Meeting.EditSource.valueOf(value);
    }

    /**
     * One column of a batch as a text array, which PostgreSQL casts to the column's own array type; an absent value
     * stays null. Chat's bulk copies pass their columns the same way.
     */
    private static <T> String[] texts(List<T> rows, java.util.function.Function<T, @Nullable String> column) {
        var values = new String[rows.size()];
        for (int i = 0; i < values.length; i++) values[i] = column.apply(rows.get(i));
        return values;
    }

    private static List<String> strings(String json) {
        return JSON.readValue(json, STRINGS);
    }

    private static @Nullable Instant instant(ResultSet r, String column) throws SQLException {
        Timestamp value = r.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
