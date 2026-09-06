package io.memoryos.ingestion;

public interface IngestionCoordinator {

    enum Outcome { COMPLETED, SKIPPED, FAILED }

    Outcome process(OperationDelivery delivery);
}
