package io.memoryos.ingestion.application;

import io.memoryos.connector.ConnectorSyncPort;
import io.memoryos.ingestion.IngestionCoordinator.Outcome;
import io.memoryos.ingestion.OperationDelivery;
import io.memoryos.ingestion.OperationWorkload;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;

public final class SourceSyncProcessor {
    private final ConnectorSyncPort sync;
    private final ScheduledExecutorService scheduler;
    private final IngestionMetrics metrics;

    public SourceSyncProcessor(ConnectorSyncPort sync, ScheduledExecutorService scheduler,
            io.micrometer.core.instrument.MeterRegistry registry) {
        this.sync = sync;
        this.scheduler = scheduler;
        this.metrics = new IngestionMetrics(registry);
    }

    public Outcome process(OperationDelivery delivery) {
        var claimed = sync.claim(delivery.tenantId(), delivery.operationId(), delivery.deliveryId());
        if (claimed.isEmpty()) return Outcome.SKIPPED;
        var work = claimed.get();
        metrics.firstClaim(OperationWorkload.SOURCE_SYNC, work.initialQueueWait());
        var renewal = scheduler.scheduleAtFixedRate(() -> {
            try {
                sync.renew(work);
            } catch (RuntimeException exception) {
                LoggerFactory.getLogger(SourceSyncProcessor.class).atWarn()
                        .addKeyValue("event", "source_sync.lease.renewal_failed")
                        .addKeyValue("operation_id", work.operationId().value())
                        .log("Source synchronization lease renewal failed");
            }
        }, 30, 30, TimeUnit.SECONDS);
        try {
            return switch (sync.execute(work)) {
                case COMPLETED -> Outcome.COMPLETED;
                case CONTINUED, SUPERSEDED -> Outcome.SKIPPED;
                case FAILED -> Outcome.FAILED;
            };
        } finally {
            renewal.cancel(false);
        }
    }
}
