package io.memoryos.objectstorage.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.memoryos.TestDatabase;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.objectstorage.ObjectStorageFailureCode;
import io.memoryos.objectstorage.ObjectUploadException;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import io.memoryos.objectstorage.UploadAuthorization;
import io.memoryos.objectstorage.UploadConstraints;
import io.memoryos.objectstorage.persistence.JdbcObjectUploadRepository;
import io.memoryos.objectstorage.persistence.JdbcStoredObjectRepository;
import io.memoryos.tenant.TenantId;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ObjectUploadLifecycleIntegrationTest {
    private static final Instant START = Instant.parse("2026-03-12T12:00:00Z");
    private static final ContentSha256 CHECKSUM = new ContentSha256(
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    );
    private static final ObjectUploadSpecification SPECIFICATION = new ObjectUploadSpecification(
            "test.txt",
            "text/plain",
            4,
            CHECKSUM
    );

    private JdbcClient jdbcClient;
    private MutableClock clock;
    private FakeObjectStorage storage;
    private DefaultObjectUploadService uploads;
    private TenantId tenantId;

    @BeforeEach
    void migrateAndSeed() throws Exception {
        var dataSource = TestDatabase.freshPostgres();
        jdbcClient = JdbcClient.create(dataSource);
        var transactionManager = new DataSourceTransactionManager(dataSource);
        clock = new MutableClock(START);
        storage = new FakeObjectStorage(clock);
        uploads = new DefaultObjectUploadService(
                new JdbcStoredObjectRepository(jdbcClient),
                new JdbcObjectUploadRepository(jdbcClient),
                storage,
                new ObjectUploadProperties(
                        Duration.ofMinutes(5),
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(1),
                        16
                ),
                new TransactionTemplate(transactionManager),
                clock
        );
        tenantId = new TenantId(UUID.randomUUID());
        jdbcClient.sql("""
                        INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference)
                        VALUES (:id, 'object-upload', 'Object upload', 'ACTIVE', 'MEM-52-TEST')
                        """)
                .param("id", tenantId.value())
                .update();
    }

    @Test
    void tenantIsolationIntegrityRetryAndReplayAreEnforced() {
        var authorization = uploads.initiate(tenantId, SPECIFICATION);
        var wrongTenant = new TenantId(UUID.randomUUID());
        var notFound = assertThrows(
                ObjectUploadException.class,
                () -> uploads.verify(wrongTenant, authorization.uploadId())
        );
        assertEquals("OBJECT_UPLOAD_NOT_FOUND", notFound.code());

        storage.replaceLast(new ObjectMetadata(5, "text/plain", CHECKSUM));
        var mismatch = assertThrows(
                ObjectUploadException.class,
                () -> uploads.verify(tenantId, authorization.uploadId())
        );
        assertEquals("OBJECT_UPLOAD_INTEGRITY_MISMATCH", mismatch.code());

        storage.replaceLast(new ObjectMetadata(
                SPECIFICATION.sizeBytes(),
                SPECIFICATION.mediaType(),
                SPECIFICATION.checksum()
        ));
        var verified = uploads.verify(tenantId, authorization.uploadId());
        assertEquals(verified.token(), uploads.verify(tenantId, authorization.uploadId()).token());
        uploads.adopt(tenantId, authorization.uploadId(), verified.token());

        var replay = assertThrows(
                ObjectUploadException.class,
                () -> uploads.adopt(tenantId, authorization.uploadId(), verified.token())
        );
        assertEquals("OBJECT_UPLOAD_CONFLICT", replay.code());
        clock.advance(Duration.ofHours(1));
        assertEquals(0, uploads.cleanupAbandoned());
        assertEquals("ADOPTED", uploadStatus(authorization.uploadId().value()));
    }

    @Test
    void providerInspectionFailureReturnsAStableRetryableUploadError() {
        var authorization = uploads.initiate(tenantId, SPECIFICATION);
        storage.failNextInspection();

        var unavailable = assertThrows(
                ObjectUploadException.class,
                () -> uploads.verify(tenantId, authorization.uploadId())
        );

        assertEquals("OBJECT_UPLOAD_STORAGE_UNAVAILABLE", unavailable.code());
        assertInstanceOf(ObjectStorageException.class, unavailable.getCause());
        assertEquals("PENDING", uploadStatus(authorization.uploadId().value()));
        var verified = uploads.verify(tenantId, authorization.uploadId());
        uploads.adopt(tenantId, authorization.uploadId(), verified.token());
    }

    @Test
    void expiredPendingUploadIsDeletedOnceAndLeavesAnIdempotencyTombstone() {
        var authorization = uploads.initiate(tenantId, SPECIFICATION);
        clock.advance(Duration.ofMinutes(6));

        assertEquals(1, uploads.cleanupAbandoned());
        assertEquals(0, uploads.cleanupAbandoned());
        assertEquals(1, storage.deleteCount());
        assertEquals("EXPIRED", uploadStatus(authorization.uploadId().value()));
        assertEquals(0, jdbcClient.sql("SELECT COUNT(*) FROM stored_objects").query(Integer.class).single());
        assertFalse(jdbcClient.sql("SELECT stored_object_id IS NOT NULL FROM object_uploads WHERE id = :id")
                .param("id", authorization.uploadId().value())
                .query(Boolean.class)
                .single());
    }

    @Test
    void oneDeleteFailureDoesNotAbortLaterRowsAndIsRetriedAfterCleanupLeaseExpiry() {
        uploads.initiate(tenantId, SPECIFICATION);
        uploads.initiate(tenantId, SPECIFICATION);
        clock.advance(Duration.ofMinutes(6));
        storage.failNextDelete();

        assertEquals(1, uploads.cleanupAbandoned());
        assertEquals(1, uploadCount("CLEANING"));
        assertEquals(1, uploadCount("EXPIRED"));

        clock.advance(Duration.ofMinutes(2));
        assertEquals(1, uploads.cleanupAbandoned());
        assertEquals(2, storage.deleteCount());
        assertEquals(2, uploadCount("EXPIRED"));
    }

    @Test
    void expiredVerificationClaimIsFencedFromCleanupCompletion() throws Exception {
        var authorization = uploads.initiate(tenantId, SPECIFICATION);
        storage.blockInspection();

        try (var executor = Executors.newSingleThreadExecutor()) {
            var verification = executor.submit(() -> uploads.verify(tenantId, authorization.uploadId()));
            storage.awaitInspection();
            clock.advance(Duration.ofMinutes(6));

            assertEquals(1, uploads.cleanupAbandoned());
            storage.releaseInspection();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, verification::get);
            var conflict = assertInstanceOf(ObjectUploadException.class, failure.getCause());
            assertEquals("OBJECT_UPLOAD_CONFLICT", conflict.code());
        }

        assertEquals("EXPIRED", uploadStatus(authorization.uploadId().value()));
    }

    private String uploadStatus(UUID id) {
        return jdbcClient.sql("SELECT status FROM object_uploads WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }
    private int uploadCount(String status) {
        return jdbcClient.sql("SELECT COUNT(*) FROM object_uploads WHERE status = :status")
                .param("status", status)
                .query(Integer.class)
                .single();
    }


    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant initial) {
            this.now = new AtomicReference<>(initial);
        }

        void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private static final class FakeObjectStorage implements ObjectStorage {
        @Override
        public void write(ObjectKey key, byte[] content, String mediaType) {
            throw new AssertionError("Browser upload tests must not use server writes");
        }
        private final Map<ObjectKey, ObjectMetadata> objects = new ConcurrentHashMap<>();
        private final Clock clock;
        private ObjectKey lastKey;
        private int deleteCount;
        private boolean failNextDelete;
        private CountDownLatch inspectionEntered;
        private CountDownLatch inspectionReleased;
        private boolean failNextInspection;

        private FakeObjectStorage(Clock clock) {
            this.clock = clock;
        }

        @Override
        public UploadAuthorization authorizeUpload(ObjectKey key, UploadConstraints constraints) {
            lastKey = key;
            objects.put(key, new ObjectMetadata(
                    constraints.sizeBytes(),
                    constraints.mediaType(),
                    constraints.checksum()
            ));
            return new UploadAuthorization(
                    "PUT",
                    URI.create("http://127.0.0.1/upload"),
                    Map.of("x-amz-checksum-sha256", constraints.checksum().base64()),
                    clock.instant().plus(Duration.ofMinutes(1))
            );
        }

        @Override
        public ObjectMetadata inspect(ObjectKey key) {
            if (failNextInspection) {
                failNextInspection = false;
                throw new ObjectStorageException(ObjectStorageFailureCode.UNAVAILABLE, true, null);
            }
            ObjectMetadata metadata = objects.get(key);
            if (inspectionEntered != null) {
                inspectionEntered.countDown();
                try {
                    if (!inspectionReleased.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("inspection was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("inspection was interrupted", exception);
                }
            }
            return metadata;
        }

        @Override
        public ObjectContent open(ObjectKey key) {
            throw new UnsupportedOperationException("not needed by upload lifecycle tests");
        }

        @Override
        public void delete(ObjectKey key) {
            if (failNextDelete) {
                failNextDelete = false;
                throw new IllegalStateException("transient object storage failure");
            }
            objects.remove(key);
            deleteCount++;
        }

        void replaceLast(ObjectMetadata metadata) {
            objects.put(lastKey, metadata);
        }

        void blockInspection() {
            inspectionEntered = new CountDownLatch(1);
            inspectionReleased = new CountDownLatch(1);
        }

        void awaitInspection() throws InterruptedException {
            if (!inspectionEntered.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("verification did not inspect the object");
            }
        }

        void releaseInspection() {
            inspectionReleased.countDown();
        }

        void failNextInspection() {
            failNextInspection = true;
        }

        void failNextDelete() {
            failNextDelete = true;
        }

        int deleteCount() {
            return deleteCount;
        }
    }
}
