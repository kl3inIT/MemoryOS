package io.memoryos.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.meeting.MeetingImportService.Line;
import io.memoryos.meeting.MeetingImportService.Transcript;
import io.memoryos.meeting.persistence.MeetingRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import io.memoryos.voice.LiveTranscriptionService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A transcript a platform wrote becomes a meeting once, belongs to the member who organized it, and is read by the
 * people the platform says attended, matched by their verified login email.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class MeetingImportTest {
    private static final Instant STARTED = Instant.parse("2026-10-05T02:00:00Z");
    private static final Instant ENDED = Instant.parse("2026-10-05T02:45:00Z");
    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private MeetingRepository meetings;
    private MeetingImportService imports;
    private MeetingService service;
    private UUID tenant, owner;

    @BeforeEach void setup() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        meetings = new MeetingRepository(jdbc);
        imports = new MeetingImportService(meetings);
        jdbc.sql("ALTER TABLE tenants DROP CONSTRAINT uq_tenants_deployment_slot").update();
        tenant = UUID.randomUUID();
        jdbc.sql("INSERT INTO tenants(id, slug, display_name, status, bootstrap_reference) VALUES (:id, :slug, 'Tasco', 'ACTIVE', 'test')")
                .param("id", tenant).param("slug", tenant.toString()).update();
        owner = member("lan@tasco.example", true);
        var authorization = mock(IamAuthorization.class, RETURNS_DEEP_STUBS);
        when(authorization.require(any(), any(), anyBoolean()).tenantId()).thenReturn(new TenantId(tenant));
        service = new MeetingService(authorization, meetings, mock(LiveTranscriptionService.class));
    }

    @AfterEach void close() { if (dataSource != null) dataSource.close(); }

    @Test void aPlatformTranscriptBecomesAnEndedMeetingWithItsVoicesNamed() {
        UUID id = imports.importTranscript(new TenantId(tenant), new ActorId(owner), transcript("conferenceRecords/a"))
                .orElseThrow();

        var meeting = service.owned(new ActorId(owner), id);
        assertEquals(Meeting.Origin.GOOGLE_MEET, meeting.origin());
        assertEquals(Meeting.Status.ENDED, meeting.status());
        assertEquals(STARTED, meeting.createdAt());
        assertEquals(ENDED, meeting.endedAt());
        assertEquals("Giao ban tuần", meeting.title());
        assertEquals("vi", meeting.language());
        assertEquals(List.of("Chị Lan", "Anh Minh"), meeting.participants());
        assertEquals(List.of(new Meeting.Speaker(Meeting.Track.IMPORTED, "1", "Chị Lan"),
                new Meeting.Speaker(Meeting.Track.IMPORTED, "2", "Anh Minh")), meeting.speakers());
        assertEquals(List.of("1", "2", "1"), meeting.utterances().stream().map(Meeting.Utterance::speaker).toList());
        assertEquals("Chốt ngân sách quý bốn.", meeting.utterances().get(1).text());
        assertTrue(meeting.utterances().stream().allMatch(line -> line.confidence() == 1 && line.spans().isEmpty()),
                "a platform gives no confidence, so nothing is marked for correction");
        assertEquals(Meeting.MinutesStatus.NONE, meeting.minutes().status(), "importing runs no model");
        assertEquals(Meeting.Origin.GOOGLE_MEET, service.list(new ActorId(owner)).getFirst().origin());
    }

    @Test void aConferenceIsImportedOnceEvenAfterItsOwnerDeletesTheMeeting() {
        var first = imports.importTranscript(new TenantId(tenant), new ActorId(owner), transcript("conferenceRecords/a"));
        assertTrue(first.isPresent());
        assertTrue(imports.importTranscript(new TenantId(tenant), new ActorId(owner), transcript("conferenceRecords/a"))
                .isEmpty());

        service.delete(new ActorId(owner), first.orElseThrow());

        assertTrue(imports.importTranscript(new TenantId(tenant), new ActorId(owner), transcript("conferenceRecords/a"))
                .isEmpty(), "a meeting its owner deleted does not come back with the next run");
        assertTrue(service.list(new ActorId(owner)).isEmpty());
        assertTrue(imports.importTranscript(new TenantId(tenant), new ActorId(owner), transcript("conferenceRecords/b"))
                .isPresent(), "another conference is still imported");
    }

    @Test void onlyThePeopleWhoAttendedReadItAndNoneOfThemEditsIt() {
        UUID attendee = member("Minh@Tasco.example", true);
        UUID unverified = member("hoa@tasco.example", false);
        UUID absent = member("an@tasco.example", true);
        UUID id = imports.importTranscript(new TenantId(tenant), new ActorId(owner), transcript("conferenceRecords/a"))
                .orElseThrow();

        var read = service.get(new ActorId(attendee), id);
        assertFalse(read.owned());
        assertEquals(3, read.utterances().size());
        assertEquals(List.of(id), service.list(new ActorId(attendee)).stream().map(Meeting.Summary::id).toList());
        assertThrows(MeetingException.class, () -> service.nameSpeaker(new ActorId(attendee), id, Meeting.Track.IMPORTED,
                "1", "Ai đó"));
        assertThrows(MeetingException.class, () -> service.delete(new ActorId(attendee), id));

        assertThrows(MeetingException.class, () -> service.get(new ActorId(unverified), id),
                "an address nobody verified matches no attendee");
        assertThrows(MeetingException.class, () -> service.get(new ActorId(absent), id));
        assertTrue(service.list(new ActorId(absent)).isEmpty());

        jdbc.sql("UPDATE actor_profiles SET email_verified = TRUE WHERE actor_id = :actor").param("actor", unverified)
                .update();
        assertEquals(id, service.get(new ActorId(unverified), id).id(), "the match is made on every read");
    }

    @Test void theMinutesAreQueuedWhenTheOwnerFirstOpensTheMeetingAndNotByAnAttendee() {
        UUID attendee = member("minh@tasco.example", true);
        UUID id = imports.importTranscript(new TenantId(tenant), new ActorId(owner), transcript("conferenceRecords/a"))
                .orElseThrow();

        assertEquals(Meeting.MinutesStatus.NONE, service.get(new ActorId(attendee), id).minutes().status());
        assertEquals(Meeting.MinutesStatus.PENDING, service.get(new ActorId(owner), id).minutes().status());

        jdbc.sql("UPDATE meeting SET minutes_status = 'READY', minutes_edited = TRUE WHERE id = :id").param("id", id)
                .update();
        var reopened = service.get(new ActorId(owner), id);
        assertEquals(Meeting.MinutesStatus.READY, reopened.minutes().status());
        assertTrue(reopened.minutes().edited(), "opening it again asks for nothing");
    }

    @Test void nothingIsImportedForSomebodyWhoIsNotAMemberOrForATranscriptNobodySpokeIn() {
        UUID outsider = UUID.randomUUID();
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", outsider).update();
        assertTrue(imports.importTranscript(new TenantId(tenant), new ActorId(outsider), transcript("conferenceRecords/a"))
                .isEmpty());
        assertTrue(imports.importTranscript(new TenantId(tenant), new ActorId(owner), transcript("conferenceRecords/a"))
                .isPresent(), "a refused owner claims nothing, so the organizer can still be given the meeting");

        var silent = new Transcript(Meeting.Origin.GOOGLE_MEET, "conferenceRecords/silent", "Họp nhanh", null, STARTED,
                ENDED, List.of(new Line("participants/1", "Chị Lan", 0, 900, "   ")), List.of());
        assertTrue(imports.importTranscript(new TenantId(tenant), new ActorId(owner), silent).isEmpty());
        assertEquals(1, service.list(new ActorId(owner)).size());
    }

    @Test void whatAPlatformSendsIsCleanedBeforeItIsStored() {
        var untidy = new Transcript(Meeting.Origin.GOOGLE_MEET, "conferenceRecords/a", "  Họp\tKPI \n", "fr-FR", STARTED,
                ENDED, List.of(new Line("participants/1", " ", 5_000, 1_000, " Xin chào. ")),
                List.of(" LAN@Tasco.example ", "lan@tasco.example", "not an address", "@tasco.example"));

        UUID id = imports.importTranscript(new TenantId(tenant), new ActorId(owner), untidy).orElseThrow();

        var meeting = service.owned(new ActorId(owner), id);
        assertEquals("HọpKPI", meeting.title());
        assertNull(meeting.language(), "a language the minutes are not written in is left to the model");
        assertEquals(new Meeting.Speaker(Meeting.Track.IMPORTED, "1", null), meeting.speakers().getFirst());
        var line = meeting.utterances().getFirst();
        assertEquals("Xin chào.", line.text());
        assertEquals(5_000, line.startMs());
        assertEquals(5_000, line.endMs(), "a line never ends before it starts");
        assertEquals(List.of("lan@tasco.example"), jdbc.sql("SELECT email FROM meeting_attendee WHERE meeting_id = :id")
                .param("id", id).query(String.class).list());
    }

    private static Transcript transcript(String externalId) {
        return new Transcript(Meeting.Origin.GOOGLE_MEET, externalId, "Giao ban tuần", "vi-VN", STARTED, ENDED, List.of(
                new Line("participants/1", "Chị Lan", 0, 4_000, "Chào mọi người."),
                new Line("participants/2", "Anh Minh", 4_500, 9_000, "Chốt ngân sách quý bốn."),
                new Line("participants/1", "Chị Lan", 9_500, 12_000, "Đồng ý.")),
                List.of("lan@tasco.example", "minh@tasco.example", "hoa@tasco.example", "khach@doitac.example"));
    }

    /** An active member with a login profile; the email is stored as the identity provider sent it. */
    private UUID member(String email, boolean verified) {
        UUID actor = UUID.randomUUID();
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", actor).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id, actor_id, role, status) VALUES (:tenant, :actor, 'MEMBER', 'ACTIVE')")
                .param("tenant", tenant).param("actor", actor).update();
        jdbc.sql("INSERT INTO external_identity_bindings(issuer, subject, actor_id) VALUES ('https://id.example', :subject, :actor)")
                .param("subject", actor.toString()).param("actor", actor).update();
        jdbc.sql("""
                INSERT INTO actor_profiles(actor_id, issuer, subject, email, email_verified, observed_at)
                VALUES (:actor, 'https://id.example', :subject, :email, :verified, now())
                """).param("actor", actor).param("subject", actor.toString()).param("email", email)
                .param("verified", verified).update();
        return actor;
    }
}
