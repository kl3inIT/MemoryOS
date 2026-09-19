package io.memoryos.ingestion.application;

import io.memoryos.connector.GoogleDriveSelectionProcessor;
import io.memoryos.connector.SharePointSelectionProcessor;
import io.memoryos.connector.SourceSelectionProcessor;
import io.memoryos.ingestion.IngestionCoordinator.Outcome;
import io.memoryos.ingestion.OperationDelivery;
import io.memoryos.ingestion.OperationWorkload;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;

/** Runs one connector's scope verification, keeping its claim alive while the provider is being read. */
public final class SelectionValidationProcessor {
    private final GoogleDriveSelectionProcessor driveSelections;
    private final SharePointSelectionProcessor sharePointSelections;
    private final ScheduledExecutorService scheduler;
    private final IngestionMetrics metrics;

    public SelectionValidationProcessor(GoogleDriveSelectionProcessor driveSelections,
            SharePointSelectionProcessor sharePointSelections, ScheduledExecutorService scheduler,
            io.micrometer.core.instrument.MeterRegistry registry) {
        this.driveSelections = driveSelections;
        this.sharePointSelections = sharePointSelections;
        this.scheduler = scheduler;
        this.metrics = new IngestionMetrics(registry);
    }

    public Outcome process(OperationDelivery delivery) {
        OperationWorkload workload = delivery.workload();
        SourceSelectionProcessor selections = switch (workload) {
            case GOOGLE_DRIVE_SELECTION_VALIDATION -> driveSelections;
            case SHAREPOINT_SELECTION_VALIDATION -> sharePointSelections;
            default -> throw new IllegalArgumentException("Workload is not a selection validation: " + workload);
        };
        var claimed = selections.claim(delivery.tenantId(), delivery.operationId(), delivery.deliveryId());
        if (claimed.isEmpty()) return Outcome.SKIPPED;
        var work = claimed.get();
        metrics.firstClaim(workload, work.initialQueueWait());
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
