package io.memoryos.retrieval;

import io.memoryos.document.DocumentChunkSet;
import io.memoryos.document.DocumentId;
import io.memoryos.document.DocumentIndexState;
import io.memoryos.iam.tenant.TenantId;
import java.util.List;
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
    boolean contains(DocumentIndexState document, String identity);
    /** All chunks of the generation are indexed, even if their metadata or access fields are stale. */
    boolean containsGeneration(DocumentIndexState document, String identity);
    /** Removes the document's chunks outside its served and current content generations. */
    void purgeObsolete(TenantId tenantId, DocumentId documentId, String identity);
    void purgeStale(String identity);
}
