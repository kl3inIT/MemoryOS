package io.memoryos.worker;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.memoryos.ingestion.IngestionCoordinator;

import org.junit.jupiter.api.Test;

class IngestionWorkerTest {

    @Test
    void clampsTheBatchAndDelegatesAvailableWork() {
        var coordinator = mock(IngestionCoordinator.class);
        var worker = new IngestionWorker(coordinator, new WorkerProperties(64));

        worker.processAvailableWork();

        verify(coordinator).processAvailable(32);
    }
}
