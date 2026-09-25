package io.memoryos.ingestion.application;

import io.memoryos.connector.GoogleDriveAclChanged;
import io.memoryos.connector.SourceAccessChanged;
import io.memoryos.document.DocumentChanged;
import io.memoryos.document.DocumentChunkPort;
import io.memoryos.document.DocumentId;
import io.memoryos.document.DocumentIndexState;
import io.memoryos.ingestion.persistence.JdbcSearchWorkRepository;
import io.memoryos.retrieval.SearchIndex;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SearchProjectionMaintenance {
    private final DocumentChunkPort documents;
    private final JdbcSearchWorkRepository work;
    private final SearchIndex index;
    private final TransactionTemplate transactions;
    /** In memory only: a restarted process begins a new pass, which repair tolerates. */
    private DocumentIndexState.@Nullable Cursor cursor;
    private final int rebuildWindow;

    public SearchProjectionMaintenance(DocumentChunkPort documents, JdbcSearchWorkRepository work, SearchIndex index,
            PlatformTransactionManager transactionManager) {
        this(documents, work, index, transactionManager, 64);
    }

    @Autowired
    public SearchProjectionMaintenance(DocumentChunkPort documents, JdbcSearchWorkRepository work, SearchIndex index,
            PlatformTransactionManager transactionManager, @Value("${memoryos.search.rebuild-window:64}") int rebuildWindow) {
        if (rebuildWindow < 1 || rebuildWindow > 1000) throw new IllegalArgumentException("invalid search rebuild window");
        this.documents = documents; this.work = work; this.index = index; this.rebuildWindow = rebuildWindow;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** The Worker's coordinator for the SEARCH work this projection queues. */
    public SearchIngestionCoordinator coordinator(TransactionTemplate transactions, ScheduledExecutorService leases,
            MeterRegistry metrics) {
        return new SearchIngestionCoordinator(work, documents, index, transactions, leases, metrics);
    }

    /** Runs in the transaction that changed the document; a rebuild in progress receives the change too. */
    @EventListener
    public void changed(DocumentChanged event) {
        for (String identity : index.identities()) work.enqueue(event, identity, false);
    }

    /** Runs in the transaction that changed Source access; membership changes need no index write. */
    @EventListener
    public void accessChanged(SourceAccessChanged event) {
        for (String identity : index.identities()) work.enqueueSourceAccess(event.tenantId(), event.sourceId(), identity);
    }

    /** Runs in the permission snapshot transaction; only SYNC Sources index provider permissions. */
    @EventListener
    public void aclChanged(GoogleDriveAclChanged event) {
        for (String identity : index.identities()) {
            work.enqueueDocumentAccess(event.tenantId(), event.sourceId(), event.documentIds(), identity);
        }
    }

    /** Repairs the PRESENT index page by page and drops work queued for indexes that are no longer active. */
    public synchronized void reconcile() {
        var identities = index.identities();
        String identity = identities.getFirst();
        work.cancelObsolete(identities);
        var page = documents.scan(identity, cursor, 32);
        // Only documents ready in the index are compared with it, all of them in one read.
        var ready = page.stream().filter(DocumentIndexState::ready).toList();
        var projections = ready.isEmpty() ? Map.<DocumentId, SearchIndex.Projection>of() : index.inspect(ready, identity);
        for (var document : page) {
            var projection = document.ready() ? projections.getOrDefault(document.documentId(), SearchIndex.Projection.INCOMPLETE) : null;
            if (projection != SearchIndex.Projection.CURRENT) {
                // A complete generation whose only drift is metadata or access keeps serving while ACCESS repairs it;
                // hiding it for a full rewrite would drop still-authorized results for the duration of the rewrite.
                boolean accessOnly = projection == SearchIndex.Projection.STALE_FIELDS;
                transactions.executeWithoutResult(_ -> {
                    if (accessOnly) {
                        work.enqueueAccessRepair(document.tenantId(), document.documentId(), document.generation(), identity);
                        return;
                    }
                    if (document.ready()) documents.markSearchPending(document.tenantId(), document.documentId(), document.generation(), identity);
                    work.enqueue(new DocumentChanged(document.tenantId(), document.documentId(), document.generation(), false), identity, true);
                });
            }
            cursor = document.cursor();
        }
        if (page.size() < 32) cursor = null;
        index.purgeStale(identity);
    }

    /**
     * Feeds the FUTURE index being rebuilt from the chunks already stored, a bounded window at a time; returns how many
     * documents were queued. Nothing is kept in memory, so a restarted worker simply continues.
     */
    public int rebuild() {
        var identities = index.identities();
        if (identities.size() < 2) return 0;
        return work.enqueueRebuild(identities.get(1), rebuildWindow);
    }
}
