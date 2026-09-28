package io.memoryos.connector.sync;

import io.memoryos.connector.ConnectorSyncPort.Work;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.source.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.source.persistence.JdbcSourceItemRepository;
import io.memoryos.connector.source.persistence.JdbcSourceRepository;
import io.memoryos.connector.source.persistence.JdbcSourceRepository.SourcePair;
import io.memoryos.connector.sync.persistence.JdbcIndexAttemptRepository;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository.FileOutcome;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository.ItemFailure;
import io.memoryos.connector.sync.persistence.SyncTarget;
import io.memoryos.objectstorage.ObjectWriteService;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One execution slice of a synchronization attempt, as a traversal sees it. Every write happens inside
 * {@link #fenced}, which locks the Source and checks that the claim is still current; once a fence finds it
 * stale the run is {@linkplain #stopped() stopped} and later fences write nothing. The methods documented as
 * running inside a fence must be called from within a {@link #fenced} action.
 */
public final class SyncRun {
    private static final Logger LOGGER = LoggerFactory.getLogger(SyncRun.class);
    /** Earlier failures a slice loads to resolve; beyond this the next slice resolves them. */
    private static final int UNRESOLVED_LIMIT = 10_000;

    private final Work work;
    private final SyncTarget target;
    private final JdbcSourceSyncRepository attempts;
    private final JdbcSourceRepository sources;
    private final JdbcSourceItemRepository items;
    private final JdbcIndexAttemptRepository indexing;
    private final JdbcSourceDocumentRepository documents;
    private final ObjectWriteService writes;
    private final TransactionTemplate transactions;
    private @Nullable Set<String> unresolved;
    private boolean stopped;
    private boolean aborted;

    SyncRun(Work work, SyncTarget target, JdbcSourceSyncRepository attempts, JdbcSourceRepository sources,
            JdbcSourceItemRepository items, JdbcIndexAttemptRepository indexing,
            JdbcSourceDocumentRepository documents, ObjectWriteService writes, TransactionTemplate transactions) {
        this.work = work;
        this.target = target;
        this.attempts = attempts;
        this.sources = sources;
        this.items = items;
        this.indexing = indexing;
        this.documents = documents;
        this.writes = writes;
        this.transactions = transactions;
    }

    public Work work() {
        return work;
    }

    /** True once a fence found the claim no longer current; nothing more is written. */
    public boolean stopped() {
        return stopped;
    }

    /** True once item failures crossed the abort threshold. */
    public boolean aborted() {
        return aborted;
    }

    /** Stops the run because the traversal found it stale outside a fence, for example a newer credential. */
    public SyncTraversal.Slice stop() {
        stopped = true;
        return SyncTraversal.Slice.STOPPED;
    }

    /**
     * Runs {@code action} in one transaction that holds the Source lock, if the claim is still current. Returns
     * empty, and stops the run, when it is not; also empty when the action returned null.
     */
    public <T> Optional<T> fenced(Function<SourcePair, @Nullable T> action) {
        if (stopped) return Optional.empty();
        return transactions.execute(_ -> {
            var pair = lock();
            if (pair.isEmpty() || !attempts.current(target, work)) {
                stopped = true;
                return Optional.<T>empty();
            }
            return Optional.ofNullable(action.apply(pair.get()));
        });
    }

    /** Inside a fence: counts a file this run observed. */
    public void observed(String providerFileId, @Nullable String name) {
        attempts.observed(work, providerFileId, name);
    }

    /**
     * Inside a fence: true, recording the file as unchanged, when the Source already holds this provider version
     * under the attempt's scope and credential.
     */
    public boolean unchanged(String providerFileId, String providerVersion) {
        var version = items.unchanged(work, providerFileId, providerVersion);
        if (version.isEmpty()) return false;
        attempts.outcome(work, providerFileId, FileOutcome.UNCHANGED,
                indexing.findLive(work.tenantId(), work.sourceId(), version.get()).isPresent());
        resolved(providerFileId);
        return true;
    }

    /** Inside a fence: records a file this run skips, for example one outside the scope. */
    public void skipped(String providerFileId) {
        attempts.outcome(work, providerFileId, FileOutcome.SKIPPED, false);
    }

    /**
     * Inside a fence: records an item failure as a run error. Returns true, and marks the run
     * {@linkplain #aborted() aborted}, when failures crossed the threshold at which Onyx fails a run.
     */
    public boolean itemFailed(ItemFailure failure) {
        LOGGER.atWarn().addKeyValue("event", "source_sync.item.failed")
                .addKeyValue("source_id", work.sourceId().value())
                .addKeyValue("error_code", failure.code())
                .log("Synchronization item failed; the run continues");
        if (attempts.itemFailed(work, failure) && !failure.skipped()) aborted = true;
        return aborted;
    }

    /** Inside a fence: removes an item the provider no longer reports. */
    public void remove(SourcePair pair, SourceItemId item) {
        items.markDeleting(work.tenantId(), pair, item);
        documents.invalidateItem(work.tenantId(), work.sourceId(), item);
        indexing.cancelForItem(work.tenantId(), work.sourceId(), item);
        sources.createCleanup(new SourceOperationId(UUID.randomUUID()), work.tenantId(),
                SourceOperationType.REMOVE_ITEM, "ITEM:" + item.value(), work.sourceId(), item, null);
    }

    /** Inside a fence: counts removed items. */
    public void removed(int count) {
        attempts.removed(work, count);
    }

    /** Inside a fence: resolves an earlier run's error for an item that failed there and was read here. */
    public void resolved(String providerFileId) {
        if (unresolved().remove(providerFileId)) attempts.resolve(work, providerFileId);
    }

    /**
     * Inside a fence: completes the attempt, {@code COMPLETED_WITH_ERRORS} when any item failed. The traversal
     * writes its own completion state in the same fence.
     */
    public void complete() {
        if (attempts.complete(target, work).isEmpty()) {
            stopped = true;
            return;
        }
        sources.recomputeStatus(work.tenantId(), work.sourceId(), false);
    }

    /**
     * Stores the content, then in one fence either recognises it as the version already held or adopts it as a
     * new version and queues it for indexing. The staged object is discarded unless it was adopted.
     */
    public Acquired acquire(Content content, AcquireHooks hooks) {
        var staged = writes.stage(work.tenantId(), new ObjectWriteService.Specification(content.filename(),
                content.mediaType(), content.text()), content.bytes());
        boolean adopted = false;
        try {
            String file = content.descriptor().providerFileId();
            var result = fenced(pair -> {
                if (!hooks.inScope()) return Acquired.OUT_OF_SCOPE;
                var same = items.sameContent(work, file, staged.object().metadata().checksum().value(),
                        staged.object().filename(), content.descriptor().providerVersion());
                if (same.isPresent()) {
                    attempts.outcome(work, file, FileOutcome.UNCHANGED,
                            indexing.findLive(work.tenantId(), work.sourceId(), same.get()).isPresent());
                    resolved(file);
                    hooks.recorded(false);
                    return Acquired.UNCHANGED;
                }
                writes.adopt(work.tenantId(), staged);
                var version = items.acceptRemote(work, pair, staged.object(), content.descriptor());
                indexing.cancelForItem(work.tenantId(), work.sourceId(), version.itemId());
                // The current Document stays retrievable until the new version publishes over the same mapping.
                indexing.create(work.tenantId(), pair, version, work.operationId());
                attempts.outcome(work, file, FileOutcome.ACQUIRED, false);
                resolved(file);
                hooks.recorded(true);
                return Acquired.ACQUIRED;
            }).orElse(Acquired.STOPPED);
            adopted = result == Acquired.ACQUIRED;
            return result;
        } finally {
            if (!adopted) writes.discard(work.tenantId(), staged);
        }
    }

    private Set<String> unresolved() {
        if (unresolved == null) {
            unresolved = new HashSet<>(attempts.unresolvedItems(work.tenantId(), work.sourceId(), null,
                    UNRESOLVED_LIMIT));
        }
        return unresolved;
    }

    private Optional<SourcePair> lock() {
        try {
            return Optional.of(sources.lock(work.tenantId(), work.sourceId()));
        } catch (SourceException exception) {
            if ("SOURCE_NOT_FOUND".equals(exception.code())) return Optional.empty();
            throw exception;
        }
    }

    /** Content read from the provider, with the descriptor its version is recorded under. */
    public record Content(SourceInputDescriptor descriptor, String filename, String mediaType, boolean text,
                          byte[] bytes) {}

    /** What the traversal does inside the acquisition fence. */
    public interface AcquireHooks {
        /** Before anything is written: false when the item left the scope meanwhile. */
        default boolean inScope() {
            return true;
        }

        /** After the outcome was recorded; {@code acquired} is false when the content was already held. */
        void recorded(boolean acquired);
    }

    public enum Acquired { ACQUIRED, UNCHANGED, OUT_OF_SCOPE, STOPPED }
}
