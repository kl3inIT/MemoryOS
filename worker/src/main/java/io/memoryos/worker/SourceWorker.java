package io.memoryos.worker;

import io.memoryos.ingestion.IndexingCoordinator;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "memoryos.worker.enabled", havingValue = "true", matchIfMissing = true)
final class SourceWorker {

    private final IndexingCoordinator coordinator;
    private final int batchSize;

    SourceWorker(
            IndexingCoordinator coordinator,
            WorkerProperties properties
    ) {
        this.coordinator = coordinator;
        this.batchSize = Math.clamp(properties.batchSize(), 1, 32);
    }

    @Scheduled(fixedDelayString = "${memoryos.worker.idle-delay:1s}")
    void processAvailableWork() {
        coordinator.processAvailable(batchSize);
    }
}
