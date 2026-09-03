package io.memoryos.ingestion;

public interface IngestionCoordinator {

    void process(OperationDelivery delivery);
}
