package io.memoryos.meeting;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.meeting.persistence.MeetingRepository;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class MeetingRepositoryTest {
    private static final int MAX = 3;
    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private MeetingRepository meetings;
    private UUID tenant, owner;

    @BeforeEach void setup() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        meetings = new MeetingRepository(jdbc);
        jdbc.sql("ALTER TABLE tenants DROP CONSTRAINT uq_tenants_deployment_slot").update();
        tenant = UUID.randomUUID();
        jdbc.sql("INSERT INTO tenants(id, slug, display_name, status, bootstrap_reference) VALUES (:id, :slug, 'Tasco', 'ACTIVE', 'test')")
                .param("id", tenant).param("slug", tenant.toString()).update();
        owner = UUID.randomUUID();
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", owner).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id, actor_id, role, status) VALUES (:tenant, :actor, 'MEMBER', 'ACTIVE')")
                .param("tenant", tenant).param("actor", owner).update();
    }

    @AfterEach void close() { if (dataSource != null) dataSource.close(); }

    @Test void minutesWhoseLastLeaseLapsedFailInsteadOfRunningForever() {
        UUID abandoned = meeting(), retrying = meeting();
        meetings.queueMinutes(tenant, abandoned);
        meetings.queueMinutes(tenant, retrying);
        jdbc.sql("UPDATE meeting SET minutes_status = 'RUNNING', minutes_attempts = :max, minutes_lease_until = now() - interval '1 minute' WHERE id = :id")
                .param("max", MAX).param("id", abandoned).update();
        jdbc.sql("UPDATE meeting SET minutes_status = 'RUNNING', minutes_attempts = 1, minutes_lease_until = now() - interval '1 minute' WHERE id = :id")
                .param("id", retrying).update();

        assertEquals(1, meetings.failAbandonedMinutes(MAX));

        var failed = meetings.find(tenant, owner, abandoned).orElseThrow();
        assertEquals(Meeting.MinutesStatus.FAILED, failed.minutesStatus());
        assertEquals("MEETING_MINUTES_FAILED", failed.minutesFailure());
        assertEquals(retrying, meetings.claimMinutes(Duration.ofMinutes(10), MAX).orElseThrow().id(),
                "a lapsed lease with attempts left is retried, not failed");
        assertTrue(meetings.claimMinutes(Duration.ofMinutes(10), MAX).isEmpty());
    }

    @Test void aRecordingWhoseLastLeaseLapsedFailsEndsTheMeetingAndIsHeldForRetirement() {
        UUID abandoned = meeting(), live = meeting();
        UUID upload = UUID.randomUUID();
        meetings.reserveAudio(tenant, abandoned, upload, "hop.m4a", "audio/mp4", 1024, null);
        assertTrue(meetings.queueAudio(tenant, abandoned, "raw/hop.m4a"));
        jdbc.sql("UPDATE meeting SET audio_status = 'RUNNING', audio_attempts = :max, audio_lease_until = now() - interval '1 minute' WHERE id = :id")
                .param("max", MAX).param("id", abandoned).update();
        meetings.reserveAudio(tenant, live, UUID.randomUUID(), "live.m4a", "audio/mp4", 1024, null);
        assertTrue(meetings.queueAudio(tenant, live, "raw/live.m4a"));
        jdbc.sql("UPDATE meeting SET audio_status = 'RUNNING', audio_attempts = :max, audio_lease_until = now() + interval '10 minutes' WHERE id = :id")
                .param("max", MAX).param("id", live).update();

        assertEquals(1, meetings.failAbandonedAudio(MAX));
        assertEquals(List.of(new MeetingRepository.HeldRecording(tenant, abandoned, upload)), meetings.heldRecordings(10),
                "the given-up recording still holds its bytes until the sweep retires them");

        var failed = meetings.find(tenant, owner, abandoned).orElseThrow();
        assertEquals(Meeting.AudioStatus.FAILED, failed.audioStatus());
        assertEquals("MEETING_RECORDING_FAILED", failed.audioFailure());
        assertEquals(Meeting.Status.ENDED, failed.status());
        assertNotNull(failed.endedAt());
        var running = meetings.find(tenant, owner, live).orElseThrow();
        assertEquals(Meeting.AudioStatus.RUNNING, running.audioStatus(), "a lease still held is left alone");
        assertEquals(Meeting.Status.TRANSCRIBING, running.status());
        assertEquals(0, meetings.failAbandonedAudio(MAX));
    }

    @Test void onlyFinishedRecordingsAreHeldAndForgettingOneLeavesANewerUploadAlone() {
        UUID done = meeting(), waiting = meeting();
        UUID doneUpload = UUID.randomUUID();
        meetings.reserveAudio(tenant, done, doneUpload, "hop.m4a", "audio/mp4", 1024, null);
        assertTrue(meetings.queueAudio(tenant, done, "raw/hop.m4a"));
        var claim = meetings.claimAudio(Duration.ofMinutes(10), MAX).orElseThrow();
        assertTrue(meetings.writeAudio(tenant, done, claim.attempts(), "SONIOX", "stt-async-v5", true, List.of()));
        meetings.reserveAudio(tenant, waiting, UUID.randomUUID(), "cho.m4a", "audio/mp4", 1024, null);

        assertEquals(List.of(new MeetingRepository.HeldRecording(tenant, done, doneUpload)), meetings.heldRecordings(10),
                "a recording still waiting or running is not the sweep's");

        meetings.forgetAudio(tenant, done, UUID.randomUUID());
        assertEquals(1, meetings.heldRecordings(10).size(), "another upload's retirement forgets nothing");
        meetings.forgetAudio(tenant, done, doneUpload);
        assertTrue(meetings.heldRecordings(10).isEmpty());
        assertTrue(meetings.audioUpload(tenant, done).isEmpty());
    }

    @Test void aTranscribedRecordingItsMinutesAndItsSharesAreWrittenInAFixedNumberOfStatements() {
        var counted = new io.memoryos.StatementCounter(dataSource);
        var batched = new MeetingRepository(JdbcClient.create(counted));
        UUID id = meeting();
        batched.reserveAudio(tenant, id, UUID.randomUUID(), "hop.m4a", "audio/mp4", 1024, null);
        assertTrue(batched.queueAudio(tenant, id, "raw/hop.m4a"));
        var claim = batched.claimAudio(Duration.ofMinutes(10), MAX).orElseThrow();
        var lines = new java.util.ArrayList<Meeting.Utterance>();
        for (int i = 0; i < 300; i++)
            lines.add(new Meeting.Utterance(UUID.randomUUID(), Meeting.Track.MIC, Integer.toString(i % 3 + 1), i * 1000L,
                    i * 1000L + 900, "Câu số " + i, 0.25 + i % 3 * 0.25, List.of(new Meeting.Span(0, 3, 0.4))));
        counted.reset();

        assertTrue(batched.writeAudio(tenant, id, claim.attempts(), "SONIOX", "stt-async-v5", true, lines));

        assertEquals(3, counted.statements().size(), "the claim fence, the speakers and the lines, however many");
        var stored = batched.utterances(tenant, id);
        assertEquals(300, stored.size());
        assertEquals(lines.get(7), stored.get(7), "every column survives the batch, spans and confidence included");
        assertEquals(3, batched.speakers(tenant, id).size());

        UUID ask = UUID.randomUUID();
        var items = List.of(
                new Meeting.MinutesItem(UUID.randomUUID(), Meeting.ItemKind.DECISION, "Chốt ngân sách", null, null,
                        "chốt", stored.getFirst().id(), false),
                new Meeting.MinutesItem(ask, Meeting.ItemKind.ACTION, "Gửi KPI", "Chị Lan", "Thứ Năm", null, null,
                        false),
                new Meeting.MinutesItem(UUID.randomUUID(), Meeting.ItemKind.TOPIC, "Ngân sách", null, null, null,
                        stored.get(2).id(), false));
        batched.queueMinutes(tenant, id);
        var minutes = batched.claimMinutes(Duration.ofMinutes(10), MAX).orElseThrow();
        counted.reset();
        assertTrue(batched.writeMinutes(tenant, id, minutes.attempts(), "Tóm tắt", "GENERAL", items));
        assertEquals(3, counted.statements().size(), "the fence, the old items out and the new ones in");
        var read = batched.minutesItems(tenant, id);
        assertEquals(3, read.size());
        assertEquals(new Meeting.MinutesItem(ask, Meeting.ItemKind.ACTION, "Gửi KPI", "Chị Lan", "Thứ Năm", null, null,
                false, false), read.stream().filter(item -> item.id().equals(ask)).findFirst().orElseThrow());

        UUID reader = UUID.randomUUID(), other = UUID.randomUUID();
        for (UUID actor : List.of(reader, other)) {
            jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", actor).update();
            jdbc.sql("INSERT INTO tenant_memberships(tenant_id, actor_id, role, status) VALUES (:tenant, :actor, 'MEMBER', 'ACTIVE')")
                    .param("tenant", tenant).param("actor", actor).update();
        }
        counted.reset();
        batched.share(tenant, id, List.of(reader, other), List.of());
        assertEquals(3, counted.statements().size(), "two clears and one insert for every member named");
        assertEquals(2, batched.readers(tenant, id).size());

        UUID run = UUID.randomUUID();
        var offers = stored.subList(0, 40).stream().map(line -> new Meeting.Correction(UUID.randomUUID(), line.id(), run,
                0, 3, "Câu", "Cầu", "", 0.5, 0.75, 0.25, false, Meeting.CorrectionStatus.PENDING)).toList();
        counted.reset();
        batched.insertCorrections(tenant, id, run, offers);
        assertEquals(1, counted.statements().size());
        assertEquals(java.util.Set.copyOf(offers), java.util.Set.copyOf(batched.corrections(tenant, id)));
    }

    @Test void aMeetingHasUtterancesOnlyOnceSomebodySpoke() {
        UUID spoken = meeting(), silent = meeting();
        meetings.insertUtterance(tenant, spoken, new Meeting.Utterance(UUID.randomUUID(), Meeting.Track.MIC, "0", 0, 1200,
                "Chào mọi người", 0.9, List.of(), null));

        assertTrue(meetings.hasUtterances(tenant, spoken));
        assertFalse(meetings.hasUtterances(tenant, silent));
        assertFalse(meetings.hasUtterances(UUID.randomUUID(), spoken), "another Tenant sees nothing");
    }

    @Test void savingNotesOrDetailsAnswersWithTheRevisionTheNextSaveNames() {
        UUID id = meeting();

        assertEquals(1, meetings.updateNotes(tenant, id, "Hỏi hạn mức"));
        assertEquals(2, meetings.updateDetails(tenant, id, "Giao ban tuần", List.of("Chị Lan")));
        assertEquals(2, meetings.find(tenant, owner, id).orElseThrow().revision());
    }

    @Test void tickingAnItemAnswersWithTheItemAsItNowReads() {
        UUID id = meeting(), other = meeting(), item = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO meeting_minutes_item(tenant_id, id, meeting_id, kind, position, text, owner)
                VALUES (:tenant, :id, :meeting, 'ACTION', 0, 'Gửi bảng KPI', 'Chị Lan')
                """).param("tenant", tenant).param("id", item).param("meeting", id).update();

        var ticked = meetings.markItem(tenant, id, item, true).orElseThrow();

        assertEquals(new Meeting.MinutesItem(item, Meeting.ItemKind.ACTION, "Gửi bảng KPI", "Chị Lan", null, null,
                null, true, false), ticked);
        assertTrue(meetings.markItem(tenant, other, item, false).isEmpty(), "another meeting's item is not there");
    }

    @Test void aTopicIsNotTickedOff() {
        UUID id = meeting(), topic = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO meeting_minutes_item(tenant_id, id, meeting_id, kind, position, text)
                VALUES (:tenant, :id, :meeting, 'TOPIC', 0, 'Ngân sách quý 4')
                """).param("tenant", tenant).param("id", topic).param("meeting", id).update();

        assertTrue(meetings.markItem(tenant, id, topic, true).isEmpty());
        assertFalse(meetings.lockItem(tenant, id, topic).orElseThrow().done());
    }

    @Test void oneVoiceIsReadWithoutTheRestOfTheMeeting() {
        UUID id = meeting();
        var later = new Meeting.Utterance(UUID.randomUUID(), Meeting.Track.MIC, "1", 5000, 6000, "Mình là Minh", 0.9);
        var first = new Meeting.Utterance(UUID.randomUUID(), Meeting.Track.MIC, "1", 0, 1000, "Chào mọi người", 0.9);
        meetings.insertUtterance(tenant, id, later);
        meetings.insertUtterance(tenant, id, first);
        meetings.insertUtterance(tenant, id, new Meeting.Utterance(UUID.randomUUID(), Meeting.Track.MIC, "2", 2000,
                3000, "Tôi là Lan", 0.9));
        meetings.insertUtterance(tenant, id, new Meeting.Utterance(UUID.randomUUID(), Meeting.Track.TAB, "1", 2500,
                3500, "Em là Hoa", 0.9));

        assertEquals(List.of(first.id(), later.id()),
                meetings.utterancesOf(tenant, id, Meeting.Track.MIC, "1").stream().map(Meeting.Utterance::id).toList());
    }

    @Test void decidingOneStretchAnswersTheLineAndTheProposalAsTheyNowStand() {
        UUID id = meeting(), run = UUID.randomUUID();
        var line = new Meeting.Utterance(UUID.randomUUID(), Meeting.Track.MIC, "1", 0, 2000,
                "Bên Tát cô đã gửi bảng KPI.", 0.5,
                List.of(new Meeting.Span(4, 10, 0.35), new Meeting.Span(18, 22, 0.4)));
        meetings.insertUtterance(tenant, id, line);
        var offered = new Meeting.Correction(UUID.randomUUID(), line.id(), run, 4, 10, "Tát cô", "Tasco",
                "Tên công ty.", 0.75, 0.75, 0.75, true, Meeting.CorrectionStatus.PENDING);
        var declined = new Meeting.Correction(UUID.randomUUID(), line.id(), run, 18, 22, "bảng", "băng", "", 0.5,
                0.5, 0.5, false, Meeting.CorrectionStatus.PENDING);
        meetings.insertCorrections(tenant, id, run, List.of(offered, declined));

        var rewritten = meetings.rewrite(tenant, id, line.id(), line.text(), "Bên Tasco đã gửi bảng KPI.",
                List.of(new Meeting.Span(17, 21, 0.4)), Meeting.EditSource.MODEL, run, owner, "MODEL");
        var accepted = meetings.accepted(tenant, offered.id(), owner, 4, 10, "Tát cô", "Tasco");
        var kept = meetings.decide(tenant, declined.id(), Meeting.CorrectionStatus.KEPT, owner);

        assertEquals(meetings.utterances(tenant, id).getFirst(), rewritten, "the line as a reader now reads it");
        assertEquals(Meeting.EditSource.MODEL, rewritten.editSource());
        assertEquals(new Meeting.Correction(offered.id(), line.id(), run, 4, 10, "Tát cô", "Tasco", "Tên công ty.",
                0.75, 0.75, 0.75, true, Meeting.CorrectionStatus.ACCEPTED), accepted);
        assertEquals(Meeting.CorrectionStatus.KEPT, kept.status());
        assertTrue(meetings.corrections(tenant, id).containsAll(List.of(accepted, kept)),
                "both answers are what the list holds");
    }

    private UUID meeting() {
        UUID id = UUID.randomUUID();
        meetings.insert(tenant, id, owner, new Meeting.Draft("Họp giao ban", Meeting.Kind.IN_PERSON, "vi", List.of(), List.of()));
        return id;
    }
}
