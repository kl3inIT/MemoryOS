package io.memoryos.ingestion;

public interface IndexingCoordinator {

    void processAvailable(int batchSize);
}
