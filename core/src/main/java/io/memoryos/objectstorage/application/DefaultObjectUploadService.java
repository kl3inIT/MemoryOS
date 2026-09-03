package io.memoryos.objectstorage.application;

import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.objectstorage.ObjectStorageFailureCode;
import io.memoryos.objectstorage.ObjectUploadAuthorization;
import io.memoryos.objectstorage.ObjectUploadCleanupPort;
import io.memoryos.objectstorage.ObjectUploadException;
import io.memoryos.objectstorage.ObjectUploadId;
import io.memoryos.objectstorage.ObjectUploadService;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import io.memoryos.objectstorage.ObjectVerificationToken;
import io.memoryos.objectstorage.StoredObjectId;
import io.memoryos.objectstorage.StoredObjectReference;
import io.memoryos.objectstorage.VerifiedObject;
import io.memoryos.objectstorage.persistence.JdbcObjectUploadRepository;
import io.memoryos.objectstorage.persistence.JdbcStoredObjectRepository;
import io.memoryos.tenant.TenantId;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DefaultObjectUploadService implements ObjectUploadService, ObjectUploadCleanupPort {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultObjectUploadService.class);

    private final JdbcStoredObjectRepository objects;
    private final JdbcObjectUploadRepository uploads;
    private final ObjectStorage storage;
    private final ObjectUploadProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public DefaultObjectUploadService(
            JdbcStoredObjectRepository objects,
            JdbcObjectUploadRepository uploads,
            ObjectStorage storage,
            ObjectUploadProperties properties,
            PlatformTransactionManager transactionManager
    ) {
        this(objects, uploads, storage, properties, new TransactionTemplate(transactionManager), Clock.systemUTC());
    }

    DefaultObjectUploadService(
            JdbcStoredObjectRepository objects,
            JdbcObjectUploadRepository uploads,
            ObjectStorage storage,
            ObjectUploadProperties properties,
            TransactionTemplate transactions,
            Clock clock
    ) {
        this.objects = Objects.requireNonNull(objects, "objects must not be null");
        this.uploads = Objects.requireNonNull(uploads, "uploads must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public ObjectUploadAuthorization initiate(TenantId tenantId, ObjectUploadSpecification specification) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(specification, "specification must not be null");
        Instant expiresAt = Instant.now(clock).plus(properties.lifetime());
        StoredObjectId storedObjectId = new StoredObjectId(UUID.randomUUID());
        ObjectUploadId uploadId = new ObjectUploadId(UUID.randomUUID());
        var key = new io.memoryos.objectstorage.ObjectKey(
                "raw/" + tenantId.value() + "/" + storedObjectId.value()
        );
        transactions.executeWithoutResult(_ -> {
            objects.create(tenantId, storedObjectId, key, specification, expiresAt);
            uploads.create(tenantId, uploadId, storedObjectId);
        });
        try {
            return new ObjectUploadAuthorization(uploadId, storage.authorizeUpload(key, specification.constraints()));
        } catch (ObjectStorageException exception) {
            throw ObjectUploadException.storageUnavailable(exception.code(), exception);
        }
    }

    @Override
    public VerifiedObject verify(TenantId tenantId, ObjectUploadId uploadId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(uploadId, "uploadId must not be null");
        Instant now = Instant.now(clock);
        var existing = requireUpload(tenantId, uploadId);
        if ("VERIFIED".equals(existing.status()) && existing.adoptionDeadline() != null
                && !existing.adoptionDeadline().isBefore(now)) {
            return verified(tenantId, existing, new ObjectVerificationToken(existing.verificationToken()));
        }
        ObjectVerificationToken token = new ObjectVerificationToken(UUID.randomUUID());
        boolean claimed = Boolean.TRUE.equals(transactions.execute(_ -> uploads.claimVerification(
                tenantId, uploadId, token, now, now.plus(properties.verificationLease())
        )));
        if (!claimed) {
            throw ObjectUploadException.conflict("object upload verification is expired, active, or already closed");
        }
        StoredObjectReference reference = objects.find(tenantId, existing.storedObjectId())
                .orElseThrow(ObjectUploadException::notFound);
        try {
            ObjectMetadata observed = storage.inspect(reference.key());
            if (!reference.metadata().equals(observed)) {
                transactions.executeWithoutResult(_ -> uploads.releaseVerification(
                        tenantId, uploadId, token, "OBJECT_METADATA_MISMATCH"
                ));
                throw ObjectUploadException.integrityMismatch();
            }
            Instant verifiedAt = Instant.now(clock);
            boolean completed = Boolean.TRUE.equals(transactions.execute(_ -> uploads.completeVerification(
                    tenantId, uploadId, token, verifiedAt, verifiedAt.plus(properties.adoptionTimeout())
            )));
            if (!completed) {
                throw ObjectUploadException.conflict("object upload verification claim was lost");
            }
            return new VerifiedObject(uploadId, reference, token);
        } catch (ObjectStorageException exception) {
            transactions.executeWithoutResult(_ -> uploads.releaseVerification(
                    tenantId, uploadId, token, "OBJECT_STORAGE_" + exception.code().name()
            ));
            if (exception.code() == ObjectStorageFailureCode.NOT_FOUND
                    || exception.code() == ObjectStorageFailureCode.PRECONDITION_FAILED) {
                throw ObjectUploadException.integrityMismatch(exception);
            }
            throw ObjectUploadException.storageUnavailable(exception.code(), exception);
        } catch (RuntimeException exception) {
            if (!(exception instanceof ObjectUploadException)) {
                transactions.executeWithoutResult(_ -> uploads.releaseVerification(
                        tenantId, uploadId, token, "OBJECT_STORAGE_INSPECTION_FAILED"
                ));
            }
            throw exception;
        }
    }

    @Override
    public void adopt(TenantId tenantId, ObjectUploadId uploadId, ObjectVerificationToken token) {
        transactions.executeWithoutResult(_ -> {
            var row = requireUpload(tenantId, uploadId);
            boolean adopted = uploads.adopt(tenantId, uploadId, token, Instant.now(clock));
            if (!adopted || !objects.activate(tenantId, row.storedObjectId())) {
                throw ObjectUploadException.conflict("verified object upload could not be adopted");
            }
        });
    }

    @Override
    public void discard(TenantId tenantId, ObjectUploadId uploadId, ObjectVerificationToken token) {
        transactions.executeWithoutResult(_ -> {
            var row = requireUpload(tenantId, uploadId);
            if (!uploads.discard(tenantId, uploadId, token, Instant.now(clock))) {
                throw ObjectUploadException.conflict("verified object upload could not be discarded");
            }
            objects.markDeletePending(tenantId, row.storedObjectId());
        });
    }

    @Override
    public void releaseAdopted(TenantId tenantId, ObjectUploadId uploadId) {
        transactions.executeWithoutResult(_ -> uploads.releaseAdopted(tenantId, uploadId));
    }

    @Override
    public int cleanupAbandoned() {
        int limit = properties.cleanupBatchSize();
        Instant now = Instant.now(clock);
        UUID token = UUID.randomUUID();
        var claimed = transactions.execute(_ -> uploads.claimAbandoned(
                now, now.plus(properties.cleanupLease()), token, limit
        ));
        int completed = 0;
        for (var row : Objects.requireNonNull(claimed)) {
            try {
                StoredObjectReference reference = objects.find(row.tenantId(), row.storedObjectId())
                        .orElseThrow(ObjectUploadException::notFound);
                storage.delete(reference.key());
                transactions.executeWithoutResult(_ -> {
                    uploads.expireClaimed(row);
                    objects.remove(row.tenantId(), row.storedObjectId());
                });
                completed++;
            } catch (RuntimeException exception) {
                LOGGER.warn(
                        "Abandoned object upload cleanup failed; the claim will be retried after lease expiry: tenant={}, upload={}",
                        row.tenantId().value(),
                        row.uploadId().value(),
                        exception
                );
            }
        }
        return completed;
    }

    private JdbcObjectUploadRepository.UploadRow requireUpload(TenantId tenantId, ObjectUploadId uploadId) {
        return uploads.find(tenantId, uploadId).orElseThrow(ObjectUploadException::notFound);
    }

    private VerifiedObject verified(
            TenantId tenantId,
            JdbcObjectUploadRepository.UploadRow row,
            ObjectVerificationToken token
    ) {
        StoredObjectReference reference = objects.find(tenantId, row.storedObjectId())
                .orElseThrow(ObjectUploadException::notFound);
        return new VerifiedObject(row.id(), reference, token);
    }
}
