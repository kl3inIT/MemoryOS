package io.memoryos.retrieval;

import io.memoryos.document.DocumentChunkSet;
import io.memoryos.document.DocumentId;
import io.memoryos.document.DocumentIndexState;
import io.memoryos.shared.TenantId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ingestion's public entry point to the search projection. Every write names the index it is for: the PRESENT index
 * searches read, or the FUTURE index rebuilt beside it while an administrator changes the embedding model.
 */
public interface SearchIndex {
    /** The PRESENT index. */
    String identity();
    /** The indexes changes are written to: PRESENT first, then the FUTURE being rebuilt, if any. */
    List<String> identities();
    void index(DocumentChunkSet document, String identity);
    void delete(TenantId tenantId, DocumentId documentId, String identity);
    /** Refreshes source metadata and access fields of an indexed generation without re-embedding or hiding it. */
    void updateAccess(TenantId tenantId, DocumentId documentId, UUID generation, String identity);
    /**
     * How each document stands in the index at its generation, read for the whole page at once. Every document of the
     * page has an entry; a missing index holds every document {@link Projection#INCOMPLETE}.
     */
    Map<DocumentId, Projection> inspect(List<DocumentIndexState> documents, String identity);
    /** Removes the document's chunks outside its served and current content generations. */
    void purgeObsolete(TenantId tenantId, DocumentId documentId, String identity);
    void purgeStale(String identity);

    enum Projection {
        /** Every chunk of the generation is indexed with the current metadata and access fields. */
        CURRENT,
        /** Every chunk of the generation is indexed, but its metadata or access fields are stale. */
        STALE_FIELDS,
        /** Chunks of the generation are missing. */
        INCOMPLETE
    }
}
