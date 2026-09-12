package io.memoryos.ingestion.application;

import io.memoryos.chat.UserFileWork;
import io.memoryos.chat.UserFileWorkPort;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractionArtifactPort;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.IngestionCoordinator;
import io.memoryos.ingestion.OperationDelivery;
import io.memoryos.ingestion.ChatFileExtractor;
import io.memoryos.objectstorage.ObjectStorage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class UserFileIngestionCoordinator implements IngestionCoordinator {
    private final UserFileWorkPort files;
    private final ChatFileExtractor extractor;
    private final ObjectStorage storage;
    private final ExtractionArtifactPort artifacts;
    private final ScheduledExecutorService leases;

    public UserFileIngestionCoordinator(UserFileWorkPort files, ChatFileExtractor extractor, ObjectStorage storage,
            ExtractionArtifactPort artifacts, ScheduledExecutorService leases) {
        this.files = files; this.extractor = extractor; this.storage = storage; this.artifacts = artifacts; this.leases = leases;
    }

    @Override
    public Outcome process(OperationDelivery delivery) {
        return files.claim(delivery.tenantId(), delivery.operationId().value(), delivery.deliveryId())
                .map(this::process).orElse(Outcome.SKIPPED);
    }

    private Outcome process(UserFileWork work) {
        var lost = new java.util.concurrent.atomic.AtomicBoolean();
        var renewal = leases.scheduleAtFixedRate(() -> {
            try { if (!files.renew(work)) lost.set(true); }
            catch (RuntimeException failure) { lost.set(true); }
        }, 30, 30, TimeUnit.SECONDS);
        try {
            if (work.action() == UserFileWork.Action.DELETE) return files.deleted(work) ? Outcome.COMPLETED : Outcome.SKIPPED;
            DocumentContent parsed;
            try (var content = storage.open(work.object().key())) {
                if (!content.metadata().equals(work.object().metadata())) throw new IllegalStateException("file metadata changed");
                parsed = extractor.extract(content.inputStream(), content.metadata().sizeBytes(), work.object().filename());
            }
            if (lost.get()) return Outcome.SKIPPED;
            var canonical = artifacts.stage(work.tenantId(), parsed);
            if (lost.get()) return Outcome.SKIPPED;
            return files.complete(work, canonical) ? Outcome.COMPLETED : Outcome.SKIPPED;
        } catch (ExtractionException exception) {
            if (lost.get()) return Outcome.SKIPPED;
            files.failed(work, "FILE_EXTRACTION_" + exception.failure().name());
            return Outcome.FAILED;
        } catch (Exception exception) {
            if (lost.get()) return Outcome.SKIPPED;
            // Provider messages may contain file data; expose only a stable failure code.
            files.failed(work, "FILE_PROCESSING_FAILED");
            return Outcome.FAILED;
        } finally {
            renewal.cancel(false);
        }
    }
}
