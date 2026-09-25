package io.memoryos.connector.sync;

import io.memoryos.BusinessException;
import io.memoryos.FailureEvidence;
import io.memoryos.connector.ConnectorSyncPort;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceRunTrigger;
import io.memoryos.connector.SourceStatus;
import io.memoryos.connector.SourceType;
import io.memoryos.connector.source.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.source.persistence.JdbcSourceItemRepository;
import io.memoryos.connector.source.persistence.JdbcSourceRepository;
import io.memoryos.connector.source.persistence.JdbcSourceRepository.SourcePair;
import io.memoryos.connector.sync.SyncTraversal.RunFailure;
import io.memoryos.connector.sync.persistence.JdbcIndexAttemptRepository;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository;
import io.memoryos.connector.sync.persistence.WorkLeases.RetryOutcome;
import io.memoryos.objectstorage.ObjectWriteService;
import io.memoryos.shared.TenantId;
import io.memoryos.connector.SourceOperationId;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The synchronization engine every connector shares. It owns the attempt: claiming and renewing it, the
 * fences every write passes, acquisition and removal of items, item errors, and how the run ends. Each
 * provider's {@link SyncTraversal} walks its content.
 *
 * <p>Failures follow Onyx ({@code run_docfetching}): a failure isolated to one item is a run error and the
 * run continues, completing {@code COMPLETED_WITH_ERRORS}; the next run retries the item and resolves the
 * error when it succeeds. More than {@value JdbcSourceSyncRepository#ITEM_FAILURE_FLOOR} failures that are also
 * more than a tenth of what the run processed abort it, and a failure not isolated to an item fails it; both
 * retry the attempt with backoff, honouring a provider's {@code Retry-After}. Pausing or deleting the Source
 * cancels the run.
 */
@Service
public class SourceSyncEngine implements ConnectorSyncPort {
    private static final Logger LOGGER = LoggerFactory.getLogger(SourceSyncEngine.class);
    /** Five retries after the first failure, as before. */
    static final int MAX_FAILURES = 6;
    static final Duration BACKOFF = Duration.ofSeconds(30);
    static final String ITEM_FAILURES_EXCEEDED = "SOURCE_SYNC_ITEM_FAILURES_EXCEEDED";

    private final JdbcSourceSyncRepository attempts;
    private final JdbcSourceRepository sources;
    private final JdbcSourceItemRepository items;
    private final JdbcIndexAttemptRepository indexing;
    private final JdbcSourceDocumentRepository documents;
    private final ObjectWriteService writes;
    private final Map<SourceType, SyncTraversal> traversals = new EnumMap<>(SourceType.class);
    private final TransactionTemplate transactions;

    public SourceSyncEngine(JdbcSourceSyncRepository attempts, JdbcSourceRepository sources,
            JdbcSourceItemRepository items, JdbcIndexAttemptRepository indexing,
            JdbcSourceDocumentRepository documents, ObjectWriteService writes, List<SyncTraversal> traversals,
            PlatformTransactionManager manager) {
        this.attempts = attempts;
        this.sources = sources;
        this.items = items;
        this.indexing = indexing;
        this.documents = documents;
        this.writes = writes;
        traversals.forEach(traversal -> this.traversals.put(traversal.type(), traversal));
        this.transactions = new TransactionTemplate(manager);
    }

    @Override
    public Optional<Work> claim(TenantId tenant, SourceOperationId operation, UUID deliveryId) {
        return Objects.requireNonNull(transactions.execute(_ -> attempts.claim(tenant, operation, deliveryId)));
    }

    @Override
    public boolean renew(Work work) {
        return Boolean.TRUE.equals(transactions.execute(_ -> attempts.renew(work)));
    }

    @Override
    public int enqueueDue(int limit) {
        int count = 0;
        for (var traversal : traversals.values()) {
            for (var due : traversal.due(limit)) {
                try {
                    if (Boolean.TRUE.equals(transactions.execute(_ -> enqueueScheduled(traversal, due)))) count++;
                } catch (BusinessException exception) {
                    transactions.executeWithoutResult(_ -> postpone(traversal, due));
                }
            }
        }
        return count;
    }

    private boolean enqueueScheduled(SyncTraversal traversal, JdbcSourceSyncRepository.DueSource due) {
        var pair = sources.lock(due.tenantId(), due.sourceId());
        if (pair.status() == SourceStatus.PAUSED) return false;
        if (!attempts.automaticSyncEnabled(traversal.target(), due.tenantId(), due.sourceId())) return false;
        long credential = traversal.credentialRevision(due.tenantId(), due.sourceId());
        if (!traversal.credentialCurrent(due.tenantId(), due.sourceId(), credential)) {
            postpone(traversal, due);
            return false;
        }
        attempts.enqueue(traversal.target(), due.tenantId(), due.sourceId(), credential,
                SourceRunTrigger.SCHEDULED, null);
        return true;
    }

    private void postpone(SyncTraversal traversal, JdbcSourceSyncRepository.DueSource due) {
        attempts.postpone(traversal.target(), due.tenantId(), due.sourceId());
        traversal.postponed(due.tenantId(), due.sourceId());
    }

    @Override
    public Result execute(Work work) {
        // One attempt table serves every connector, so the run belongs to whichever one owns the Source.
        SourceType type;
        try {
            type = Objects.requireNonNull(transactions.execute(_ -> sources.type(work.tenantId(), work.sourceId())));
        } catch (SourceException exception) {
            if ("SOURCE_NOT_FOUND".equals(exception.code())) return Result.CANCELLED;
            throw exception;
        }
        var traversal = Objects.requireNonNull(traversals.get(type), () -> "no synchronization for " + type);
        var run = new SyncRun(work, traversal.target(), attempts, sources, items, indexing, documents, writes,
                transactions);
        SyncTraversal.Slice slice;
        try {
            slice = traversal.walk(run);
        } catch (RuntimeException exception) {
            if (run.stopped()) return stopped(traversal, work);
            return failed(traversal, work, exception);
        }
        if (run.stopped() || slice == SyncTraversal.Slice.STOPPED) return stopped(traversal, work);
        return switch (slice) {
            case COMPLETED -> Result.COMPLETED;
            case CONTINUE -> run.fenced(_ -> {
                attempts.continuation(work);
                return Result.CONTINUED;
            }).orElseGet(() -> stopped(traversal, work));
            case ABORTED -> aborted(traversal, work);
            case STOPPED -> stopped(traversal, work);
        };
    }

    /** Item failures crossed the threshold: the attempt is retried, counting failures afresh. */
    private Result aborted(SyncTraversal traversal, Work work) {
        LOGGER.atWarn().addKeyValue("event", "source_sync.run.aborted")
                .addKeyValue("source_id", work.sourceId().value())
                .addKeyValue("source_type", traversal.type().name())
                .log("Synchronization run aborted after too many item failures; retrying");
        settle(work, pair -> {
            if (pair == null) return;
            attempts.restartFailureWindow(work);
            retry(traversal, work, RunFailure.retry(ITEM_FAILURES_EXCEEDED, null),
                    "More than " + JdbcSourceSyncRepository.ITEM_FAILURE_FLOOR
                            + " items, and more than a tenth of those processed, failed.", null);
        });
        return Result.FAILED;
    }

    private Result failed(SyncTraversal traversal, Work work, RuntimeException exception) {
        var failure = traversal.classify(exception);
        String detail = FailureEvidence.detail(exception);
        switch (failure.kind()) {
            case RECONNECT -> {
                traversal.authenticationFailed(work);
                settle(work, pair -> {
                    if (pair != null && attempts.terminal(traversal.target(), work, "FAILED", failure.code(),
                            exception.getMessage(), detail)) {
                        traversal.ended(work, true, failure.code());
                    }
                });
            }
            case FAIL -> settle(work, pair -> {
                if (pair != null && attempts.terminal(traversal.target(), work, "FAILED", failure.code(),
                        exception.getMessage(), detail)) {
                    traversal.ended(work, true, failure.code());
                }
            });
            case RETRY -> {
                LOGGER.atWarn().addKeyValue("event", "source_sync.run.failed")
                        .addKeyValue("source_id", work.sourceId().value())
                        .addKeyValue("source_type", traversal.type().name())
                        .addKeyValue("error_code", failure.code())
                        .addKeyValue("error_type", exception.getClass().getName())
                        .log("Synchronization run failed; retrying");
                settle(work, pair -> {
                    if (pair != null) retry(traversal, work, failure, exception.getMessage(), detail);
                });
            }
        }
        return Result.FAILED;
    }

    private void retry(SyncTraversal traversal, Work work, RunFailure failure, @Nullable String message,
            @Nullable String detail) {
        var backoff = failure.retryAfter() != null && failure.retryAfter().compareTo(BACKOFF) > 0
                ? failure.retryAfter() : BACKOFF;
        var outcome = attempts.retry(work, failure.code(), message, detail, MAX_FAILURES, backoff);
        if (outcome == RetryOutcome.EXHAUSTED) {
            attempts.recordFailure(traversal.target(), work, failure.code());
            traversal.ended(work, true, failure.code());
        }
    }

    /**
     * The claim is no longer current. A paused or deleted Source cancels the run; anything else, such as a newer
     * scope, credential or claim, supersedes it.
     */
    private Result stopped(SyncTraversal traversal, Work work) {
        return Objects.requireNonNull(transactions.execute(_ -> {
            var pair = lock(work);
            if (pair == null) return Result.CANCELLED;
            String code = pair.status() == SourceStatus.PAUSED ? "SOURCE_PAUSED"
                    : pair.status() == SourceStatus.DELETING ? "SOURCE_DELETING" : null;
            String status = code == null ? "SUPERSEDED" : "CANCELLED";
            if (attempts.terminal(traversal.target(), work, status, code, null, null)) {
                traversal.ended(work, false, code);
            }
            return code == null ? Result.SUPERSEDED : Result.CANCELLED;
        }));
    }

    private void settle(Work work, java.util.function.Consumer<@Nullable SourcePair> action) {
        transactions.executeWithoutResult(_ -> action.accept(lock(work)));
    }

    private @Nullable SourcePair lock(Work work) {
        try {
            return sources.lock(work.tenantId(), work.sourceId());
        } catch (SourceException exception) {
            if ("SOURCE_NOT_FOUND".equals(exception.code())) return null;
            throw exception;
        }
    }
}
