package io.memoryos.objectstorage.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.objectstorage.ObjectStorageFailureCode;
import io.memoryos.objectstorage.ObjectWriteService.Specification;
import io.memoryos.objectstorage.ObjectWriteService.StagedObject;
import io.memoryos.objectstorage.StoredObjectReference;
import io.memoryos.objectstorage.UploadAuthorization;
import io.memoryos.objectstorage.UploadConstraints;
import io.memoryos.objectstorage.persistence.JdbcObjectWriteRepository;
import io.memoryos.objectstorage.persistence.JdbcStoredObjectRepository;
import io.memoryos.iam.TenantId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ObjectWriteLifecycleIntegrationTest {
    private static final Specification BINARY = new Specification("test.txt", "text/plain", false);
    private static final byte[] CONTENT = "test".getBytes(StandardCharsets.UTF_8);

    private JdbcClient jdbc;
    private TransactionTemplate transactions;
    private MutableClock clock;
    private FakeStorage storage;
    private JdbcStoredObjectRepository objects;
    private DefaultObjectWriteService writes;
    private TenantId tenant;
    private HikariDataSource dataSource;

    @AfterEach
    void closeDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeEach
    void migrateAndSeed() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        clock = new MutableClock();
        storage = new FakeStorage();
        objects = new JdbcStoredObjectRepository(jdbc);
        writes = new DefaultObjectWriteService(objects, new JdbcObjectWriteRepository(jdbc), storage,
                new ObjectUploadProperties(Duration.ofMinutes(5), Duration.ofSeconds(30),
                        Duration.ofMinutes(5), Duration.ofMinutes(1), 20), transactions, clock);
        tenant = new TenantId(UUID.randomUUID());
        jdbc.sql("""
                INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference)
                VALUES (:id, 'raw-writes', 'Raw writes', 'ACTIVE', 'RAW-WRITE-TEST')
                """).param("id", tenant.value()).update();
    }

    @Test
    void timeoutRetainsDurableReservationAcrossCleanupAndArbitrarilyLatePut() {
        storage.beforePut = key -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, jdbc.sql("""
                    SELECT COUNT(*) FROM object_writes w JOIN stored_objects o
                        ON o.tenant_id = w.tenant_id AND o.id = w.stored_object_id
                    WHERE o.object_key = :key AND w.status = 'WRITING'
                    """).param("key", key.value()).query(Integer.class).single());
        };
        storage.timeoutPut = true;
        assertThrows(ObjectStorageException.class, () -> writes.stage(tenant, BINARY, CONTENT));
        ObjectKey key = storage.lastKey;

        assertEquals(1, writes.cleanup(20));
        assertEquals(1, trackedWrites());
        assertFalse(storage.objects.containsKey(key));
        clock.advance(Duration.ofDays(60));
        storage.finishLatePut();
        assertEquals(1, writes.cleanup(20));
        assertFalse(storage.objects.containsKey(key));
        assertEquals(1, trackedWrites());

        clock.advance(Duration.ofDays(2));
        storage.finishLatePut();
        assertEquals(1, writes.cleanup(20));
        assertFalse(storage.objects.containsKey(key));
        assertEquals(1, trackedWrites());
    }

    @Test
    void positiveCompletionDuringCleanupCannotForgetAnObjectWrittenAfterDelete() throws Exception {
        var putEntered = new CountDownLatch(1);
        var putReleased = new CountDownLatch(1);
        var deleteEntered = new CountDownLatch(1);
        var deleteReleased = new CountDownLatch(1);
        storage.beforePut = _ -> { putEntered.countDown(); await(putReleased); };
        storage.afterDelete = () -> { deleteEntered.countDown(); await(deleteReleased); };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> writes.stage(tenant, BINARY, CONTENT));
            try {
                await(putEntered);
                clock.advance(Duration.ofMinutes(10));
                var cleaner = executor.submit(() -> writes.cleanup(20));
                await(deleteEntered);
                putReleased.countDown();
                var failure = assertThrows(ExecutionException.class, () -> writer.get(10, TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, failure.getCause());
                deleteReleased.countDown();
                assertEquals(1, cleaner.get(10, TimeUnit.SECONDS));
                assertTrue(storage.objects.containsKey(storage.lastKey));
                assertEquals(1, trackedWrites());
            } finally {
                putReleased.countDown();
                deleteReleased.countDown();
            }
        }
        clock.advance(Duration.ofDays(2));
        storage.afterDelete = () -> {};
        assertEquals(1, writes.cleanup(20));
        assertFalse(storage.objects.containsKey(storage.lastKey));
        assertEquals(0, trackedWrites());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM stored_objects").query(Integer.class).single());
    }

    @Test
    void adoptionIsTenantTokenAndMetadataFencedAndRollsBackWithAcceptance() {
        StagedObject staged = writes.stage(tenant, BINARY, CONTENT);
        assertThrows(IllegalStateException.class, () -> writes.adopt(tenant, staged));
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(_ ->
                writes.adopt(new TenantId(UUID.randomUUID()), staged)));
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(_ ->
                writes.adopt(tenant, new StagedObject(staged.object(), UUID.randomUUID()))));
        var forged = new StoredObjectReference(staged.object().id(), staged.object().key(), "other.txt",
                staged.object().metadata());
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(_ ->
                writes.adopt(tenant, new StagedObject(forged, staged.token()))));

        transactions.executeWithoutResult(status -> {
            writes.adopt(tenant, staged);
            status.setRollbackOnly();
        });
        clock.advance(Duration.ofMinutes(6));
        assertEquals(1, writes.cleanup(20));
        assertFalse(storage.objects.containsKey(staged.object().key()));
        assertEquals(0, trackedWrites());
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(_ -> writes.adopt(tenant, staged)));
    }

    @Test
    void acceptedReferencesSurviveCleanupAndCannotBeReleasedBeforeVersionRemoval() {
        StagedObject staged = writes.stage(tenant, BINARY, CONTENT);
        transactions.executeWithoutResult(_ -> {
            writes.adopt(tenant, staged);
            reference(staged);
        });
        clock.advance(Duration.ofDays(2));
        assertEquals(0, writes.cleanup(20));
        assertThrows(IllegalStateException.class, () -> writes.discard(tenant, staged));
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(_ ->
                writes.releaseAdopted(tenant, staged.object().id())));
        assertTrue(storage.objects.containsKey(staged.object().key()));

        transactions.executeWithoutResult(_ -> objects.markDeletePending(tenant, staged.object().id()));
        storage.delete(staged.object().key());
        transactions.executeWithoutResult(_ -> {
            jdbc.sql("DELETE FROM connector_item_versions WHERE stored_object_id = :id")
                    .param("id", staged.object().id().value()).update();
            writes.releaseAdopted(tenant, staged.object().id());
            objects.remove(tenant, staged.object().id());
        });
        assertEquals(0, trackedWrites());
        assertEquals(0, writes.cleanup(20));
    }

    @Test
    void unexpectedReferenceAlsoPreventsAbandonedCleanup() {
        StagedObject staged = writes.stage(tenant, BINARY, CONTENT);
        reference(staged);
        clock.advance(Duration.ofMinutes(6));
        assertEquals(0, writes.cleanup(20));
        assertTrue(storage.objects.containsKey(staged.object().key()));
        assertEquals(1, trackedWrites());
    }

    @Test
    void integrityFailureRemainsUnadoptableAndTrackedForCleanup() {
        storage.inspectOverride = new ObjectMetadata(CONTENT.length, "application/json", hash(CONTENT));
        assertThrows(ObjectStorageException.class, () -> writes.stage(tenant, BINARY, CONTENT));
        assertEquals(1, writes.cleanup(20));
        assertFalse(storage.objects.containsKey(storage.lastKey));
        assertEquals(1, trackedWrites());
    }

    @Test
    void binaryInputsAdmitOneHundredMiBWithoutChangingNativeSnapshotBounds() {
        StagedObject staged = writes.stage(tenant, BINARY, CONTENT);
        reference(staged);
        UUID objectId = staged.object().id().value();
        assertEquals(1, jdbc.sql("""
                UPDATE stored_objects SET size_bytes = 104857600 WHERE id = :id
                """).param("id", objectId).update());
        assertEquals(1, jdbc.sql("""
                UPDATE connector_item_versions SET size_bytes = 104857600 WHERE stored_object_id = :id
                """).param("id", objectId).update());
        assertEquals(104857600L, jdbc.sql("""
                SELECT size_bytes FROM stored_objects WHERE id = :id
                """).param("id", objectId).query(Long.class).single());
        assertEquals(104857600L, jdbc.sql("""
                SELECT size_bytes FROM connector_item_versions WHERE stored_object_id = :id
                """).param("id", objectId).query(Long.class).single());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                UPDATE stored_objects SET size_bytes = 104857601 WHERE id = :id
                """).param("id", objectId).update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                UPDATE connector_item_versions SET size_bytes = 104857601 WHERE stored_object_id = :id
                """).param("id", objectId).update());

        assertEquals(1, jdbc.sql("""
                UPDATE stored_objects SET input_kind = 'NATIVE_SNAPSHOT', size_bytes = 33554432 WHERE id = :id
                """).param("id", objectId).update());
        assertEquals(1, jdbc.sql("""
                UPDATE connector_item_versions
                SET input_format = 'GOOGLE_SHEETS', provider_file_id = 'sheet',
                    scope_revision = 1, credential_revision = 1, size_bytes = 33554432
                WHERE stored_object_id = :id
                """).param("id", objectId).update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                UPDATE stored_objects SET size_bytes = 33554433 WHERE id = :id
                """).param("id", objectId).update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                UPDATE connector_item_versions SET size_bytes = 33554433 WHERE stored_object_id = :id
                """).param("id", objectId).update());
    }

    @Test
    void binaryWritesRejectOneByteBeyondOneHundredMiB() {
        byte[] content = new byte[104857601];
        assertThrows(IllegalArgumentException.class, () -> writes.stage(tenant, BINARY, content));
        assertEquals(0, trackedWrites());
        assertTrue(storage.objects.isEmpty());
    }

    @Test
    void nativeStorageRetainsItsOwnBoundsAndCannotAuthorizeBrowserUploads() {
        var nativeSpecification = new Specification("sheet.json", "application/json", true);
        assertThrows(IllegalArgumentException.class,
                () -> writes.stage(tenant, nativeSpecification, new byte[33554433]));
        StagedObject nativeObject = writes.stage(tenant, nativeSpecification, CONTENT);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                INSERT INTO object_uploads (id, tenant_id, stored_object_id, status)
                VALUES (:id, :tenant, :object, 'PENDING')
                """).param("id", UUID.randomUUID()).param("tenant", tenant.value())
                .param("object", nativeObject.object().id().value()).update());
        assertEquals(CONTENT.length, storage.inspect(nativeObject.object().key()).sizeBytes());
        writes.discard(tenant, nativeObject);
        assertEquals(1, writes.cleanup(20));
        assertEquals(0, trackedWrites());
    }

    private void reference(StagedObject staged) {
        UUID connectorId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO connectors (id, tenant_id, name, connector_type, status)
                VALUES (:id, :tenant, 'Raw lifecycle', 'FILE', 'ACTIVE')
                """).param("id", connectorId).param("tenant", tenant.value()).update();
        jdbc.sql("""
                INSERT INTO connector_items (id, tenant_id, connector_id, content_sha256, status)
                VALUES (:id, :tenant, :connector, :hash, 'PENDING')
                """).param("id", itemId).param("tenant", tenant.value()).param("connector", connectorId)
                .param("hash", staged.object().metadata().checksum().value()).update();
        jdbc.sql("""
                INSERT INTO connector_item_versions (id, tenant_id, connector_id, connector_item_id,
                    revision_number, filename, content_sha256, size_bytes, stored_object_id)
                VALUES (:id, :tenant, :connector, :item, 1, :filename, :hash, :size, :object)
                """).param("id", UUID.randomUUID()).param("tenant", tenant.value()).param("connector", connectorId)
                .param("item", itemId).param("filename", staged.object().filename())
                .param("hash", staged.object().metadata().checksum().value())
                .param("size", staged.object().metadata().sizeBytes()).param("object", staged.object().id().value()).update();
    }

    private int trackedWrites() {
        return jdbc.sql("SELECT COUNT(*) FROM object_writes").query(Integer.class).single();
    }

    private static ContentSha256 hash(byte[] bytes) {
        try {
            return new ContentSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("concurrent storage operation timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-03-12T12:00:00Z"));
        void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    private static final class FakeStorage implements ObjectStorage {
        private final Map<ObjectKey, ObjectMetadata> objects = new ConcurrentHashMap<>();
        private Consumer<ObjectKey> beforePut = _ -> {};
        private Runnable afterDelete = () -> {};
        private boolean timeoutPut;
        private ObjectKey lastKey;
        private ObjectMetadata lateMetadata;
        private ObjectMetadata inspectOverride;

        @Override
        public void write(ObjectKey key, byte[] content, String mediaType) {
            lastKey = key;
            beforePut.accept(key);
            lateMetadata = new ObjectMetadata(content.length, mediaType, hash(content));
            if (timeoutPut) throw new ObjectStorageException(ObjectStorageFailureCode.UNAVAILABLE, true, null);
            objects.put(key, lateMetadata);
        }

        void finishLatePut() { objects.put(lastKey, lateMetadata); }

        @Override
        public ObjectMetadata inspect(ObjectKey key) {
            if (inspectOverride != null) return inspectOverride;
            ObjectMetadata metadata = objects.get(key);
            if (metadata == null) throw new ObjectStorageException(ObjectStorageFailureCode.NOT_FOUND, false, null);
            return metadata;
        }

        @Override
        public void delete(ObjectKey key) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            objects.remove(key);
            afterDelete.run();
        }

        @Override
        public UploadAuthorization authorizeUpload(ObjectKey key, UploadConstraints constraints) {
            throw new AssertionError("Server acquisition must not request browser authorization");
        }

        @Override
        public ObjectContent open(ObjectKey key) { throw new UnsupportedOperationException(); }
    }
}
