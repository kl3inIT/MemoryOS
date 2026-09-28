package io.memoryos.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.meeting.persistence.MeetingRepository;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectUploadAuthorization;
import io.memoryos.objectstorage.ObjectUploadException;
import io.memoryos.objectstorage.ObjectUploadId;
import io.memoryos.objectstorage.ObjectUploadPurpose;
import io.memoryos.objectstorage.ObjectUploadService;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import io.memoryos.objectstorage.ObjectVerificationToken;
import io.memoryos.objectstorage.VerifiedObject;
import io.memoryos.shared.TenantId;
import io.memoryos.voice.BatchTranscriptionService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * The bytes of an uploaded recording are retired by a sweep over the meetings that are done with them, not by the
 * step that finished them: a process that stops between committing the transcript (or the failure) and retiring the
 * upload leaves the meeting holding it, and the next pass retires it.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class MeetingRecordingRetirementTest {
    private static final int MAX = MeetingRecordingService.MAX_ATTEMPTS;
    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private MeetingRepository meetings;
    private RecordingUploads uploads;
    private MeetingRecordingService recordings;
    private UUID tenant, owner;

    @BeforeEach void setup() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        meetings = new MeetingRepository(jdbc);
        uploads = new RecordingUploads();
        recordings = new MeetingRecordingService(mock(IamAuthorization.class), meetings, uploads,
                mock(ObjectStorage.class), mock(BatchTranscriptionService.class), mock(MeetingService.class),
                new DataSourceTransactionManager(dataSource));
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

    @Test void aRecordingLeftHeldByAStoppedProcessIsRetiredByTheNextPass() {
        UUID transcribed = meeting(), failed = meeting(), waiting = meeting();
        // Both finish and commit; the process then stops before either upload is retired.
        UUID transcribedUpload = queued(transcribed);
        var claim = meetings.claimAudio(Duration.ofMinutes(10), MAX).orElseThrow();
        assertEquals(transcribed, claim.id());
        assertTrue(meetings.writeAudio(tenant, transcribed, claim.attempts(), "SONIOX", "stt-async-v5", true, List.of()));
        UUID failedUpload = queued(failed);
        jdbc.sql("UPDATE meeting SET audio_status = 'RUNNING', audio_attempts = :max, audio_lease_until = now() - interval '1 minute' WHERE id = :id")
                .param("max", MAX).param("id", failed).update();
        assertEquals(1, meetings.failAbandonedAudio(MAX));
        UUID waitingUpload = UUID.randomUUID();
        meetings.reserveAudio(tenant, waiting, waitingUpload, "cho.m4a", "audio/mp4", 1024, null);

        assertEquals(2, recordings.retireHeld());

        assertEquals(Set.of(transcribedUpload, failedUpload), Set.copyOf(uploads.retired));
        assertTrue(meetings.audioUpload(tenant, transcribed).isEmpty());
        assertTrue(meetings.audioUpload(tenant, failed).isEmpty());
        assertEquals(waitingUpload, meetings.audioUpload(tenant, waiting).orElseThrow(),
                "a recording still waiting for its bytes is not the sweep's");
        assertEquals(0, recordings.retireHeld(), "nothing is retired twice");
    }

    @Test void anUploadAlreadyRetiredIsOnlyForgottenAndAFailedRetirementIsTriedAgain() {
        UUID retiredBefore = meeting(), unreachable = meeting();
        UUID retiredUpload = queued(retiredBefore);
        var first = meetings.claimAudio(Duration.ofMinutes(10), MAX).orElseThrow();
        assertTrue(meetings.writeAudio(tenant, first.id(), first.attempts(), "SONIOX", "stt-async-v5", true, List.of()));
        UUID unreachableUpload = queued(unreachable);
        var second = meetings.claimAudio(Duration.ofMinutes(10), MAX).orElseThrow();
        assertTrue(meetings.writeAudio(tenant, second.id(), second.attempts(), "SONIOX", "stt-async-v5", true, List.of()));
        // The first was retired by a pass that stopped before forgetting it; the second's storage is down.
        uploads.failures.put(retiredUpload, () -> ObjectUploadException.conflict("upload is not adopted"));
        uploads.failures.put(unreachableUpload, () -> new IllegalStateException("database unavailable"));

        assertEquals(1, recordings.retireHeld());
        assertTrue(meetings.audioUpload(tenant, retiredBefore).isEmpty());
        assertEquals(unreachableUpload, meetings.audioUpload(tenant, unreachable).orElseThrow());

        uploads.failures.clear();
        assertEquals(1, recordings.retireHeld());
        assertTrue(meetings.audioUpload(tenant, unreachable).isEmpty());
        assertEquals(List.of(unreachableUpload), uploads.retired);
    }

    private UUID meeting() {
        UUID id = UUID.randomUUID();
        meetings.insert(tenant, id, owner, new Meeting.Draft("Họp giao ban", Meeting.Kind.IN_PERSON, "vi", List.of(), List.of()));
        return id;
    }

    private UUID queued(UUID meeting) {
        UUID upload = UUID.randomUUID();
        meetings.reserveAudio(tenant, meeting, upload, "hop.m4a", "audio/mp4", 1024, null);
        assertTrue(meetings.queueAudio(tenant, meeting, "raw/" + upload));
        return upload;
    }

    /** Object storage as far as retiring goes: what was retired, and the uploads that answer with a failure. */
    private static final class RecordingUploads implements ObjectUploadService {
        final List<UUID> retired = new CopyOnWriteArrayList<>();
        final Map<UUID, Supplier<RuntimeException>> failures = new ConcurrentHashMap<>();

        @Override public void retireAdopted(TenantId tenantId, ObjectUploadId uploadId) {
            var failure = failures.get(uploadId.value());
            if (failure != null) throw failure.get();
            retired.add(uploadId.value());
        }

        @Override public ObjectUploadAuthorization initiate(TenantId tenantId, ObjectUploadSpecification specification) {
            throw new UnsupportedOperationException();
        }

        @Override public VerifiedObject verify(TenantId tenantId, ObjectUploadId uploadId) {
            throw new UnsupportedOperationException();
        }

        @Override public VerifiedObject verify(TenantId tenantId, ObjectUploadId uploadId, ObjectUploadPurpose purpose) {
            throw new UnsupportedOperationException();
        }

        @Override public ObjectUploadAuthorization resume(TenantId tenantId, ObjectUploadId uploadId, ObjectUploadPurpose purpose) {
            throw new UnsupportedOperationException();
        }

        @Override public void adopt(TenantId tenantId, ObjectUploadId uploadId, ObjectVerificationToken token) {
            throw new UnsupportedOperationException();
        }

        @Override public void discard(TenantId tenantId, ObjectUploadId uploadId, ObjectVerificationToken token) {
            throw new UnsupportedOperationException();
        }

        @Override public void releaseAdopted(TenantId tenantId, ObjectUploadId uploadId) {
            throw new UnsupportedOperationException();
        }
    }
}
