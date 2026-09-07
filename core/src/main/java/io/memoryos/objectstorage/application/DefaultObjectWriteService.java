package io.memoryos.objectstorage.application;

import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.objectstorage.ObjectStorageFailureCode;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import io.memoryos.objectstorage.ObjectWriteService;
import io.memoryos.objectstorage.StoredObjectId;
import io.memoryos.objectstorage.StoredObjectReference;
import io.memoryos.objectstorage.persistence.JdbcObjectWriteRepository;
import io.memoryos.objectstorage.persistence.JdbcStoredObjectRepository;
import io.memoryos.tenant.TenantId;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DefaultObjectWriteService implements ObjectWriteService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultObjectWriteService.class);
    private static final long MAX_NATIVE_SIZE_BYTES = 32L * 1024 * 1024;
    private static final Duration TOMBSTONE_RETRY = Duration.ofDays(1);

    private final JdbcStoredObjectRepository objects;
    private final JdbcObjectWriteRepository writes;
    private final ObjectStorage storage;
    private final ObjectUploadProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public DefaultObjectWriteService(JdbcStoredObjectRepository objects, JdbcObjectWriteRepository writes,
            ObjectStorage storage, ObjectUploadProperties properties, PlatformTransactionManager transactionManager) {
        this(objects, writes, storage, properties, new TransactionTemplate(transactionManager), Clock.systemUTC());
    }

    DefaultObjectWriteService(JdbcStoredObjectRepository objects, JdbcObjectWriteRepository writes,
            ObjectStorage storage, ObjectUploadProperties properties, TransactionTemplate transactions, Clock clock) {
        this.objects = Objects.requireNonNull(objects, "objects must not be null");
        this.writes = Objects.requireNonNull(writes, "writes must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public StagedObject stage(TenantId tenantId, Specification specification, byte[] bytes) {
        requireOutsideTransaction();
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(specification, "specification must not be null");
        Objects.requireNonNull(bytes, "bytes must not be null");
        long limit = specification.nativeSnapshot() ? MAX_NATIVE_SIZE_BYTES : ObjectUploadSpecification.MAX_SIZE_BYTES;
        if (bytes.length < 1 || bytes.length > limit) throw new IllegalArgumentException("raw object exceeds bounds");
        String filename = boundedText(specification.filename(), 255, "filename");
        String mediaType = boundedText(specification.mediaType(), 160, "mediaType");
        var id = new StoredObjectId(UUID.randomUUID());
        var key = new ObjectKey("raw/" + tenantId.value() + "/" + id.value());
        var metadata = new ObjectMetadata(bytes.length, mediaType, checksum(bytes));
        var staged = new StagedObject(new StoredObjectReference(id, key, filename, metadata), UUID.randomUUID());
        Instant deadline = Instant.now(clock).plus(properties.lifetime());
        transactions.executeWithoutResult(_ -> {
            objects.create(tenantId, id, key, filename, metadata, specification.nativeSnapshot(), deadline);
            writes.reserve(tenantId, id, staged.token(), deadline);
        });
        try {
            storage.write(key, bytes, mediaType);
            if (!metadata.equals(storage.inspect(key))) {
                throw new ObjectStorageException(ObjectStorageFailureCode.PRECONDITION_FAILED, false, null);
            }
            Instant now = Instant.now(clock);
            boolean completed = Boolean.TRUE.equals(transactions.execute(_ -> writes.finishWrite(
                    tenantId, id, staged.token(), now, now.plus(properties.adoptionTimeout()))));
            if (!completed) throw new IllegalStateException("raw object write claim expired or was closed");
            return staged;
        } catch (RuntimeException exception) {
            try {
                transactions.executeWithoutResult(_ -> {
                    if (writes.discard(tenantId, id, staged.token(), Instant.now(clock))) {
                        objects.markDeletePending(tenantId, id);
                    }
                });
            } catch (RuntimeException persistenceFailure) {
                exception.addSuppressed(persistenceFailure);
            }
            throw exception;
        }
    }

    @Override
    public void adopt(TenantId tenantId, StagedObject staged) {
        requireTransaction();
        transactions.executeWithoutResult(_ -> {
            requireReference(tenantId, staged);
            if (!writes.adopt(tenantId, staged.object().id(), staged.token(), Instant.now(clock))
                    || !objects.activate(tenantId, staged.object().id())) {
                throw new IllegalStateException("raw object cannot be adopted by this claim");
            }
        });
    }

    @Override
    public void discard(TenantId tenantId, StagedObject staged) {
        transactions.executeWithoutResult(_ -> {
            requireReference(tenantId, staged);
            if (!writes.discard(tenantId, staged.object().id(), staged.token(), Instant.now(clock))) {
                throw new IllegalStateException("raw object cannot be discarded by this claim");
            }
            objects.markDeletePending(tenantId, staged.object().id());
        });
    }

    @Override
    public void releaseAdopted(TenantId tenantId, StoredObjectId objectId) {
        requireTransaction();
        transactions.executeWithoutResult(_ -> {
            if (!writes.releaseAdopted(tenantId, objectId)) {
                throw new IllegalStateException("raw object is not adopted or remains referenced");
            }
        });
    }

    @Override
    public int cleanup(int limit) {
        requireOutsideTransaction();
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("cleanup limit must be between 1 and 100");
        Instant now = Instant.now(clock);
        var claimed = transactions.execute(_ -> {
            var rows = writes.claimCleanup(now, now.plus(properties.cleanupLease()), UUID.randomUUID(), limit);
            rows.forEach(row -> objects.markDeletePending(row.tenantId(), row.objectId()));
            return rows;
        });
        int completed = 0;
        for (var row : Objects.requireNonNull(claimed)) {
            try {
                storage.delete(row.key());
                transactions.executeWithoutResult(_ -> {
                    if (writes.finishCleanup(row, Instant.now(clock).plus(TOMBSTONE_RETRY))) {
                        objects.remove(row.tenantId(), row.objectId());
                    }
                });
                completed++;
            } catch (RuntimeException exception) {
                LOGGER.atWarn().addKeyValue("event", "object_write.cleanup.retry")
                        .addKeyValue("stored_object_id", row.objectId().value())
                        .addKeyValue("error_type", exception.getClass().getName())
                        .log("Raw object cleanup failed; retry after lease expiry");
            }
        }
        return completed;
    }

    private void requireReference(TenantId tenantId, StagedObject staged) {
        Objects.requireNonNull(staged, "staged must not be null");
        Objects.requireNonNull(staged.token(), "token must not be null");
        var actual = objects.find(tenantId, staged.object().id());
        if (actual.isEmpty() || !actual.get().equals(staged.object())) {
            throw new IllegalStateException("raw object is absent or does not match its reservation");
        }
    }

    private static String boundedText(String value, int limit, String name) {
        String text = Objects.requireNonNull(value, name + " must not be null").trim();
        if (text.isEmpty() || text.length() > limit) throw new IllegalArgumentException(name + " exceeds bounds");
        return text;
    }

    private static ContentSha256 checksum(byte[] bytes) {
        try {
            return new ContentSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("raw object adoption and release require the owning transaction");
        }
    }

    private static void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("raw object IO must run outside a transaction");
        }
    }
}
