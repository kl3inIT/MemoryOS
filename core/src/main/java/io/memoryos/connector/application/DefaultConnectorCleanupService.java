package io.memoryos.connector.application;

import io.memoryos.connector.CleanupObject;
import io.memoryos.connector.CleanupWork;
import io.memoryos.connector.ConnectorCleanupPort;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.persistence.JdbcCleanupAttemptRepository;
import io.memoryos.connector.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.persistence.JdbcSourceRepository;
import io.memoryos.connector.persistence.JdbcSourceUploadRepository;
import io.memoryos.document.DocumentCommandPort;
import io.memoryos.document.DocumentId;
import io.memoryos.objectstorage.ObjectUploadService;
import io.memoryos.objectstorage.StoredObjectRegistry;
import io.memoryos.tenant.TenantId;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultConnectorCleanupService implements ConnectorCleanupPort {
    private final JdbcCleanupAttemptRepository attempts;
    private final JdbcSourceRepository sources;
    private final JdbcSourceDocumentRepository sourceDocuments;
    private final JdbcSourceUploadRepository sourceUploads;
    private final DocumentCommandPort documents;
    private final ObjectUploadService objectUploads;
    private final StoredObjectRegistry storedObjects;

    public DefaultConnectorCleanupService(
            JdbcCleanupAttemptRepository attempts,
            JdbcSourceRepository sources,
            JdbcSourceDocumentRepository sourceDocuments,
            JdbcSourceUploadRepository sourceUploads,
            DocumentCommandPort documents,
            ObjectUploadService objectUploads,
            StoredObjectRegistry storedObjects
    ) {
        this.attempts = Objects.requireNonNull(attempts, "attempts must not be null");
        this.sources = Objects.requireNonNull(sources, "sources must not be null");
        this.sourceDocuments = Objects.requireNonNull(sourceDocuments, "sourceDocuments must not be null");
        this.sourceUploads = Objects.requireNonNull(sourceUploads, "sourceUploads must not be null");
        this.documents = Objects.requireNonNull(documents, "documents must not be null");
        this.objectUploads = Objects.requireNonNull(objectUploads, "objectUploads must not be null");
        this.storedObjects = Objects.requireNonNull(storedObjects, "storedObjects must not be null");
    }

    @Override
    @Transactional
    public Optional<CleanupWork> claim(TenantId tenantId, SourceOperationId operationId, UUID deliveryId) {
        return attempts.claim(tenantId, operationId, deliveryId);
    }

    @Override
    @Transactional
    public boolean retry(CleanupWork work, String errorCode, int maxAttempts, Duration backoff) {
        return attempts.retry(work, errorCode, maxAttempts, backoff);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CleanupObject> objects(CleanupWork work) {
        List<JdbcSourceUploadRepository.AdoptedObject> adopted = switch (work.type()) {
            case REMOVE_ITEM -> sourceUploads.findForItem(
                    work.tenantId(),
                    work.sourceId(),
                    Objects.requireNonNull(work.itemId(), "REMOVE_ITEM requires itemId")
            );
            case DELETE_SOURCE -> sourceUploads.findForSource(work.tenantId(), work.sourceId());
            default -> throw new IllegalStateException("unsupported cleanup operation: " + work.type());
        };
        return adopted.stream()
                .map(value -> new CleanupObject(value.uploadId(), value.object()))
                .toList();
    }

    @Override
    @Transactional
    public boolean execute(CleanupWork work) {
        if (!attempts.ownsClaim(work)) {
            return false;
        }
        if (work.type() == SourceOperationType.DELETE_SOURCE && !deleteSource(work)) {
            return attempts.complete(work, "SUPERSEDED", null);
        }
        if (work.type() == SourceOperationType.REMOVE_ITEM) {
            removeItem(work);
        } else if (work.type() != SourceOperationType.DELETE_SOURCE) {
            throw new IllegalStateException("unsupported cleanup operation: " + work.type());
        }
        return attempts.complete(work, "SUCCEEDED", null);
    }

    @Override
    @Transactional
    public boolean fail(CleanupWork work, String errorCode) {
        return attempts.complete(work, "FAILED", safeErrorCode(errorCode));
    }

    private void removeItem(CleanupWork work) {
        SourceItemId itemId = Objects.requireNonNull(work.itemId(), "REMOVE_ITEM requires itemId");
        List<JdbcSourceUploadRepository.AdoptedObject> adopted =
                sourceUploads.findForItem(work.tenantId(), work.sourceId(), itemId);
        adopted.forEach(value -> {
            sourceUploads.remove(work.tenantId(), work.sourceId(), value.uploadId());
            objectUploads.releaseAdopted(work.tenantId(), value.uploadId());
        });
        sourceUploads.removeRemainingForItem(work.tenantId(), work.sourceId(), itemId);
        List<UUID> documentIds = sourceDocuments.removeItemMappings(work.tenantId(), work.sourceId(), itemId);
        attempts.removeItemRows(work);
        adopted.forEach(value -> storedObjects.remove(work.tenantId(), value.object().id()));
        documents.removeUnreferenced(work.tenantId(), documentIds.stream().map(DocumentId::new).toList());
        sources.recomputeStatus(work.tenantId(), work.sourceId(), false);
    }

    private boolean deleteSource(CleanupWork work) {
        UUID connectorId = attempts.findConnectorId(work);
        if (connectorId == null) {
            return false;
        }
        List<JdbcSourceUploadRepository.AdoptedObject> adopted =
                sourceUploads.findForSource(work.tenantId(), work.sourceId());
        adopted.forEach(value -> {
            sourceUploads.remove(work.tenantId(), work.sourceId(), value.uploadId());
            objectUploads.releaseAdopted(work.tenantId(), value.uploadId());
        });
        sourceUploads.removeRemainingForSource(work.tenantId(), work.sourceId());
        List<UUID> documentIds = sourceDocuments.removeSourceMappings(work.tenantId(), work.sourceId());
        attempts.deleteSourceRows(work, connectorId);
        adopted.forEach(value -> storedObjects.remove(work.tenantId(), value.object().id()));
        documents.removeUnreferenced(work.tenantId(), documentIds.stream().map(DocumentId::new).toList());
        return true;
    }

    private static String safeErrorCode(String errorCode) {
        if (errorCode == null) {
            return null;
        }
        return errorCode.length() <= 64 ? errorCode : errorCode.substring(0, 64);
    }
}
