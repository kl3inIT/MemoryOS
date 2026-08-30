package io.memoryos.worker;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.memoryos.ingestion.IndexingCoordinator;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class SourceWorkerTest {

    @Test
    void clampsTheBatchAndDelegatesAvailableWork() {
        var coordinator = mock(IndexingCoordinator.class);
        var worker = new SourceWorker(
                coordinator,
                new WorkerProperties(true, 64, Duration.ofSeconds(1))
        );

        worker.processAvailableWork();

        verify(coordinator).processAvailable(32);
    }
}
