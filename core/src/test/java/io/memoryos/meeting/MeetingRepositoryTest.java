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

    @Test void aRecordingWhoseLastLeaseLapsedFailsEndsTheMeetingAndIsReturnedForRetirement() {
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

        assertEquals(List.of(new MeetingRepository.AbandonedAudio(tenant, abandoned, upload)),
                meetings.failAbandonedAudio(MAX));

        var failed = meetings.find(tenant, owner, abandoned).orElseThrow();
        assertEquals(Meeting.AudioStatus.FAILED, failed.audioStatus());
        assertEquals("MEETING_RECORDING_FAILED", failed.audioFailure());
        assertEquals(Meeting.Status.ENDED, failed.status());
        assertNotNull(failed.endedAt());
        var running = meetings.find(tenant, owner, live).orElseThrow();
        assertEquals(Meeting.AudioStatus.RUNNING, running.audioStatus(), "a lease still held is left alone");
        assertEquals(Meeting.Status.TRANSCRIBING, running.status());
        assertTrue(meetings.failAbandonedAudio(MAX).isEmpty());
    }

    @Test void aMeetingHasUtterancesOnlyOnceSomebodySpoke() {
        UUID spoken = meeting(), silent = meeting();
        meetings.insertUtterance(tenant, spoken, new Meeting.Utterance(UUID.randomUUID(), Meeting.Track.MIC, "0", 0, 1200,
                "Chào mọi người", 0.9, List.of(), null));

        assertTrue(meetings.hasUtterances(tenant, spoken));
        assertFalse(meetings.hasUtterances(tenant, silent));
        assertFalse(meetings.hasUtterances(UUID.randomUUID(), spoken), "another Tenant sees nothing");
    }

    private UUID meeting() {
        UUID id = UUID.randomUUID();
        meetings.insert(tenant, id, owner, new Meeting.Draft("Họp giao ban", Meeting.Kind.IN_PERSON, "vi", List.of(), List.of()));
        return id;
    }
}
