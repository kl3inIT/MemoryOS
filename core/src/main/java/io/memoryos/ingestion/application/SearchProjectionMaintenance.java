package io.memoryos.ingestion.application;

import io.memoryos.connector.SourceAccessChanged;
import io.memoryos.document.DocumentChanged;
import io.memoryos.document.DocumentChunkPort;
import io.memoryos.ingestion.persistence.JdbcSearchWorkRepository;
import io.memoryos.retrieval.SearchIndex;
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
    private String cursor = "";

    public SearchProjectionMaintenance(DocumentChunkPort documents, JdbcSearchWorkRepository work, SearchIndex index,
            PlatformTransactionManager transactionManager) {
        this.documents = documents; this.work = work; this.index = index;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @EventListener
    public void changed(DocumentChanged event) { work.enqueue(event, index.identity(), false); }

    /** Runs in the transaction that changed Source access; membership changes need no index write. */
    @EventListener
    public void accessChanged(SourceAccessChanged event) { work.enqueueSourceAccess(event.tenantId(), event.sourceId(), index.identity()); }

    public synchronized void reconcile() {
        work.cancelObsolete(index.identity());
        var page = documents.scan(index.identity(), cursor, 32);
        for (var document : page) {
            if (!document.ready() || !index.contains(document)) {
                // A complete generation whose only drift is metadata or access keeps serving while ACCESS repairs it;
                // hiding it for a full rewrite would drop still-authorized results for the duration of the rewrite.
                boolean accessOnly = document.ready() && index.containsGeneration(document);
                transactions.executeWithoutResult(_ -> {
                    if (accessOnly) {
                        work.enqueueAccessRepair(document.tenantId(), document.documentId(), document.generation(), index.identity());
                        return;
                    }
                    if (document.ready()) documents.markSearchPending(document.tenantId(), document.documentId(), document.generation());
                    work.enqueue(new DocumentChanged(document.tenantId(), document.documentId(), document.generation(), false), index.identity(), true);
                });
            }
            cursor = document.cursor();
        }
        if (page.size() < 32) cursor = "";
        index.purgeStale();
    }
}
