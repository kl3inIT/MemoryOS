package io.memoryos.ingestion.application;

import io.memoryos.document.DocumentChunkPort;
import io.memoryos.ingestion.IngestionCoordinator;
import io.memoryos.ingestion.OperationDelivery;
import io.memoryos.ingestion.persistence.JdbcSearchWorkRepository;
import io.memoryos.retrieval.SearchIndex;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

public final class SearchIngestionCoordinator implements IngestionCoordinator {
    private final JdbcSearchWorkRepository work;
    private final DocumentChunkPort documents;
    private final SearchIndex index;
    private final TransactionTemplate transactions;
    private final ScheduledExecutorService scheduler;
    private final MeterRegistry metrics;

    public SearchIngestionCoordinator(JdbcSearchWorkRepository work, DocumentChunkPort documents, SearchIndex index,
            TransactionTemplate transactions, ScheduledExecutorService scheduler, MeterRegistry metrics) {
        this.work = work; this.documents = documents; this.index = index;
        this.transactions = transactions; this.scheduler = scheduler; this.metrics = metrics;
    }

    @Override
    public Outcome process(OperationDelivery delivery) {
        var claimed = Objects.requireNonNull(transactions.execute(_ -> {
            var value = work.claim(delivery, index.identity());
            value.filter(c -> !c.removed()).ifPresent(c -> documents.markSearchPending(c.tenantId(), c.documentId(), c.generation()));
            return value;
        }), "Search claim transaction returned no outcome");
        if (claimed.isEmpty()) return Outcome.SKIPPED;
        var claim = claimed.orElseThrow();
        var lost = new AtomicBoolean(false);
        var lease = scheduler.scheduleAtFixedRate(() -> {
            try { if (!work.renew(claim)) lost.set(true); }
            catch (RuntimeException failure) { lost.set(true); }
        }, 30, 30, TimeUnit.SECONDS);
        long start = System.nanoTime();
        String result = "failed";
        try {
            if (claim.removed()) index.delete(claim.tenantId(), claim.documentId());
            else {
                var chunks = documents.prepare(claim.tenantId(), claim.documentId(), claim.generation());
                if (chunks.isEmpty()) {
                    work.finish(claim, "CANCELLED", "SEARCH_OBSOLETE");
                    result = "obsolete";
                    return Outcome.SKIPPED;
                }
                if (lost.get()) return Outcome.SKIPPED;
                index.index(chunks.orElseThrow());
            }
            if (lost.get()) return Outcome.SKIPPED;
            boolean completed = Boolean.TRUE.equals(transactions.execute(status -> {
                if (!work.finish(claim, "SUCCESS", null)) return false;
                if (!claim.removed() && !documents.markSearchReady(claim.tenantId(), claim.documentId(), claim.generation(), index.identity())) {
                    status.setRollbackOnly();
                    return false;
                }
                return true;
            }));
            result = completed ? "success" : "obsolete";
            return completed ? Outcome.COMPLETED : Outcome.SKIPPED;
        } catch (RuntimeException failure) {
            transactions.executeWithoutResult(_ -> {
                boolean finished = work.finish(claim, claim.attempts() < 3 ? "NOT_STARTED" : "FAILED", "SEARCH_INDEX_FAILED");
                if (finished && !claim.removed()) documents.markSearchFailed(claim.tenantId(), claim.documentId(), claim.generation());
            });
            LoggerFactory.getLogger(getClass()).atWarn().addKeyValue("event", "search.index.failed")
                    .addKeyValue("operation_id", claim.id()).addKeyValue("error_type", failure.getClass().getName())
                    .log("Search indexing failed; durable retry retained");
            return Outcome.FAILED;
        } finally {
            lease.cancel(false);
            metrics.timer("memoryos.search.index.duration", "outcome", result).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}
