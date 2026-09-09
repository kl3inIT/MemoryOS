package io.memoryos.ingestion.application;

import io.memoryos.connector.GoogleDriveSelectionProcessor;
import io.memoryos.ingestion.IngestionCoordinator.Outcome;
import io.memoryos.ingestion.OperationDelivery;
import io.memoryos.ingestion.OperationWorkload;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;

public final class SelectionValidationProcessor {
    private final GoogleDriveSelectionProcessor selections;
    private final ScheduledExecutorService scheduler;
    private final IngestionMetrics metrics;

    public SelectionValidationProcessor(GoogleDriveSelectionProcessor selections, ScheduledExecutorService scheduler,
            io.micrometer.core.instrument.MeterRegistry registry) {
        this.selections = selections;
        this.scheduler = scheduler;
        this.metrics = new IngestionMetrics(registry);
    }

    public Outcome process(OperationDelivery delivery) {
        var claimed = selections.claim(delivery.tenantId(), delivery.operationId(), delivery.deliveryId());
        if (claimed.isEmpty()) return Outcome.SKIPPED;
        var work = claimed.get();
        metrics.firstClaim(OperationWorkload.GOOGLE_DRIVE_SELECTION_VALIDATION, work.initialQueueWait());
        var renewal = scheduler.scheduleAtFixedRate(() -> {
            try {
                selections.renew(work);
            } catch (RuntimeException exception) {
                LoggerFactory.getLogger(SelectionValidationProcessor.class).atWarn()
                        .addKeyValue("event", "selection_validation.lease.renewal_failed")
                        .addKeyValue("operation_id", work.operationId().value())
                        .log("Selection validation lease renewal failed");
            }
        }, 30, 30, TimeUnit.SECONDS);
        try {
            return switch (selections.execute(work)) {
                case COMPLETED -> Outcome.COMPLETED;
                case CONTINUED, SUPERSEDED -> Outcome.SKIPPED;
                case FAILED -> Outcome.FAILED;
            };
        } finally {
            renewal.cancel(false);
        }
    }
}
