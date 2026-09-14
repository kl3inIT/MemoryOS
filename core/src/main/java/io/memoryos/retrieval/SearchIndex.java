package io.memoryos.retrieval;

import io.memoryos.document.DocumentChunkSet;
import io.memoryos.document.DocumentId;
import io.memoryos.document.DocumentIndexState;
import io.memoryos.iam.tenant.TenantId;

/** Ingestion's public entry point to the search projection. */
public interface SearchIndex {
    String identity();
    void index(DocumentChunkSet document);
    void delete(TenantId tenantId, DocumentId documentId);
    /** Refreshes source metadata and access fields of an indexed generation without re-embedding or hiding it. */
    void updateAccess(TenantId tenantId, DocumentId documentId, java.util.UUID generation);
    boolean contains(DocumentIndexState document);
    /** All chunks of the generation are indexed, even if their metadata or access fields are stale. */
    boolean containsGeneration(DocumentIndexState document);
    /** Removes the document's chunks outside its served and current content generations. */
    void purgeObsolete(TenantId tenantId, DocumentId documentId);
    void purgeStale();
}
