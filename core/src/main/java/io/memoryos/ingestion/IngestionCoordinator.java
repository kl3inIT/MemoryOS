package io.memoryos.ingestion;

public interface IngestionCoordinator {

    void processAvailable(int batchSize);
}
